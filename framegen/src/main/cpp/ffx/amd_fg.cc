#include "amd_fg.h"

#include <FidelityFX/host/backends/vk/ffx_vk.h>
#include <FidelityFX/host/ffx_frameinterpolation.h>
#include <FidelityFX/host/ffx_opticalflow.h>

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

extern void (*zerofg_ffx_job_hook)(VkCommandBuffer, const wchar_t*);  // in the vendored ffx_vk.cpp

namespace amdfg {

namespace {

// Two backends, as AMD's own frame generation provider sets it up: a shared one
// owning the resources passed between effects, and one for OF + FI.
constexpr size_t kSharedContexts = 1;
constexpr size_t kEffectContexts = 2;

FfxResource Res(VkImage image, uint32_t w, uint32_t h, VkFormat format, VkImageUsageFlags usage,
                FfxResourceStates state, const wchar_t* name) {
  VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  ci.imageType = VK_IMAGE_TYPE_2D;
  ci.format = format;
  ci.extent = {w, h, 1};
  ci.mipLevels = 1;
  ci.arrayLayers = 1;
  ci.samples = VK_SAMPLE_COUNT_1_BIT;
  ci.usage = usage;
  return ffxGetResourceVK(reinterpret_cast<void*>(image), ffxGetImageResourceDescriptionVK(image, ci), name, state);
}

}  // namespace

struct Engine::Impl {
  uint32_t width = 0, height = 0;
  uint32_t of_width = 0, of_height = 0;  // the optical flow's input size
  VkFormat format = VK_FORMAT_UNDEFINED;
  std::vector<uint8_t> scratch_shared, scratch_fx;
  FfxInterface shared{}, fx{};
  uint32_t shared_id = 0;
  bool shared_made = false, of_made = false, fi_made = false;
  FfxOpticalflowContext of{};
  FfxFrameInterpolationContext fi{};
  // of vector, of scd, dilated depth/mv and reconstructed depth (x2, toggled
  // each frame like the provider does), and our all-zero depth + motion vectors.
  enum { kOfVector, kOfScd, kDilDepth0, kDilMv0, kRecDepth0, kDilDepth1, kDilMv1, kRecDepth1, kZeroDepth, kZeroMv, kCount };
  FfxResourceInternal res[kCount]{};
  bool res_made[kCount]{};
  uint64_t frame_id = 0;
  uint32_t toggle = 0;
  bool have_prev = false;

  // Per-pass profile: a timestamp before the first pass and after each one.
  static constexpr uint32_t kMaxStamps = 96;
  VkDevice device = VK_NULL_HANDLE;
  PFN_vkCmdWriteTimestamp cmd_ts = nullptr;
  PFN_vkCmdResetQueryPool cmd_reset = nullptr;
  PFN_vkGetQueryPoolResults get_results = nullptr;
  PFN_vkDestroyQueryPool destroy_pool = nullptr;
  VkQueryPool prof_pool = VK_NULL_HANDLE;
  float ts_period = 0;
  int prof_every = 0, prof_count = 0;
  bool prof_pending = false;
  VkCommandBuffer prof_cmd = VK_NULL_HANDLE;
  std::vector<std::string> prof_labels;  // label of stamp i (i >= 1)
  static Impl* recording;

  static void Hook(VkCommandBuffer cmd, const wchar_t* label) {
    Impl* im = recording;
    if (!im || cmd != im->prof_cmd || im->prof_labels.size() + 1 >= kMaxStamps) return;
    std::string n;
    for (const wchar_t* c = label; c && *c; ++c) n += char(*c < 128 ? *c : '?');
    im->prof_labels.push_back(n);
    im->cmd_ts(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, im->prof_pool, uint32_t(im->prof_labels.size()));
  }

  ~Impl() {
    if (prof_pool) destroy_pool(device, prof_pool, nullptr);
    if (fi_made) ffxFrameInterpolationContextDestroy(&fi);
    if (of_made) ffxOpticalflowContextDestroy(&of);
    if (shared_made) {
      for (int i = 0; i < kCount; ++i)
        if (res_made[i]) shared.fpDestroyResource(&shared, res[i], shared_id);
      shared.fpDestroyBackendContext(&shared, shared_id);
    }
  }

  FfxResource Get(int i) { return shared.fpGetResource(&shared, res[i]); }

  bool Make(int i, FfxCreateResourceDescription d, const wchar_t* name) {
    d.name = name;
    if (shared.fpCreateResource(&shared, &d, shared_id, &res[i]) != FFX_OK) return false;
    res_made[i] = true;
    return true;
  }
};

Engine::Impl* Engine::Impl::recording = nullptr;

Engine::Engine(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
Engine::~Engine() = default;

std::unique_ptr<Engine> Engine::Create(VkPhysicalDevice phys, VkDevice device, PFN_vkGetDeviceProcAddr gdpa,
                                       uint32_t width, uint32_t height, VkFormat format, std::string* err,
                                       uint32_t of_width, uint32_t of_height) {
  auto fail = [&](const char* what, int code) -> std::unique_ptr<Engine> {
    if (err) *err = std::string(what) + " failed: " + std::to_string(code);
    return nullptr;
  };
  auto im = std::make_unique<Impl>();
  im->width = width;
  im->height = height;
  im->of_width = of_width ? of_width : width;
  im->of_height = of_height ? of_height : height;
  im->format = format;
  if (ffxGetSurfaceFormatVK(format) == FFX_SURFACE_FORMAT_UNKNOWN) return fail("surface format", int(format));

  im->device = device;
  im->cmd_ts = reinterpret_cast<PFN_vkCmdWriteTimestamp>(gdpa(device, "vkCmdWriteTimestamp"));
  im->cmd_reset = reinterpret_cast<PFN_vkCmdResetQueryPool>(gdpa(device, "vkCmdResetQueryPool"));
  im->get_results = reinterpret_cast<PFN_vkGetQueryPoolResults>(gdpa(device, "vkGetQueryPoolResults"));
  im->destroy_pool = reinterpret_cast<PFN_vkDestroyQueryPool>(gdpa(device, "vkDestroyQueryPool"));
  if (auto create_pool = reinterpret_cast<PFN_vkCreateQueryPool>(gdpa(device, "vkCreateQueryPool"))) {
    VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qi.queryCount = Impl::kMaxStamps;
    if (create_pool(device, &qi, nullptr, &im->prof_pool) != VK_SUCCESS) im->prof_pool = VK_NULL_HANDLE;
  }

  VkDeviceContext dc{device, phys, gdpa};
  FfxDevice fdev = ffxGetDeviceVK(&dc);
  im->scratch_shared.resize(ffxGetScratchMemorySizeVK(phys, kSharedContexts));
  im->scratch_fx.resize(ffxGetScratchMemorySizeVK(phys, kEffectContexts));
  FfxErrorCode e = ffxGetInterfaceVK(&im->shared, fdev, im->scratch_shared.data(), im->scratch_shared.size(), kSharedContexts);
  if (e != FFX_OK) return fail("ffxGetInterfaceVK (shared)", e);
  e = ffxGetInterfaceVK(&im->fx, fdev, im->scratch_fx.data(), im->scratch_fx.size(), kEffectContexts);
  if (e != FFX_OK) return fail("ffxGetInterfaceVK", e);
  e = im->shared.fpCreateBackendContext(&im->shared, FFX_EFFECT_SHAREDAPIBACKEND, nullptr, &im->shared_id);
  if (e != FFX_OK) return fail("shared backend context", e);
  im->shared_made = true;

  FfxOpticalflowContextDescription od{};
  od.backendInterface = im->fx;
  od.resolution = {im->of_width, im->of_height};
  e = ffxOpticalflowContextCreate(&im->of, &od);
  if (e != FFX_OK) return fail("ffxOpticalflowContextCreate", e);
  im->of_made = true;

  FfxFrameInterpolationContextDescription fd{};
  fd.backendInterface = im->fx;
  // Depth 0 with inverted depth reads as "everything at the far plane", so the
  // depth-based disocclusion logic sees one flat, still scene.
  fd.flags = FFX_FRAMEINTERPOLATION_ENABLE_DEPTH_INVERTED | FFX_FRAMEINTERPOLATION_ENABLE_DEPTH_INFINITE |
             FFX_FRAMEINTERPOLATION_ENABLE_DISPLAY_RESOLUTION_MOTION_VECTORS;
  fd.maxRenderSize = {width, height};
  fd.displaySize = {width, height};
  fd.backBufferFormat = ffxGetSurfaceFormatVK(format);
  fd.previousInterpolationSourceFormat = fd.backBufferFormat;
  e = ffxFrameInterpolationContextCreate(&im->fi, &fd);
  if (e != FFX_OK) return fail("ffxFrameInterpolationContextCreate", e);
  im->fi_made = true;

  FfxOpticalflowSharedResourceDescriptions ofr{};
  e = ffxOpticalflowGetSharedResourceDescriptions(&im->of, &ofr);
  if (e != FFX_OK) return fail("OF shared resources", e);
  FfxFrameInterpolationSharedResourceDescriptions fir{};
  e = ffxFrameInterpolationGetSharedResourceDescriptions(&im->fi, &fir);
  if (e != FFX_OK) return fail("FI shared resources", e);
  if (!im->Make(Impl::kOfVector, ofr.opticalFlowVector, L"zfg_of_vector") ||
      !im->Make(Impl::kOfScd, ofr.opticalFlowSCD, L"zfg_of_scd") ||
      !im->Make(Impl::kDilDepth0, fir.dilatedDepth, L"zfg_dilated_depth0") ||
      !im->Make(Impl::kDilMv0, fir.dilatedMotionVectors, L"zfg_dilated_mv0") ||
      !im->Make(Impl::kRecDepth0, fir.reconstructedPrevNearestDepth, L"zfg_rec_depth0") ||
      !im->Make(Impl::kDilDepth1, fir.dilatedDepth, L"zfg_dilated_depth1") ||
      !im->Make(Impl::kDilMv1, fir.dilatedMotionVectors, L"zfg_dilated_mv1") ||
      !im->Make(Impl::kRecDepth1, fir.reconstructedPrevNearestDepth, L"zfg_rec_depth1"))
    return fail("shared resource", 0);

  FfxCreateResourceDescription z{};
  z.heapType = FFX_HEAP_TYPE_DEFAULT;
  z.resourceDescription.type = FFX_RESOURCE_TYPE_TEXTURE2D;
  z.resourceDescription.width = width;
  z.resourceDescription.height = height;
  z.resourceDescription.depth = 1;
  z.resourceDescription.mipCount = 1;
  z.resourceDescription.flags = FFX_RESOURCE_FLAGS_NONE;
  z.resourceDescription.usage = FFX_RESOURCE_USAGE_READ_ONLY;
  z.initialState = FFX_RESOURCE_STATE_COMPUTE_READ;
  z.resourceDescription.format = FFX_SURFACE_FORMAT_R32_FLOAT;
  z.initData = FfxResourceInitData::FfxResourceInitValue(size_t(width) * height * 4, 0);
  if (!im->Make(Impl::kZeroDepth, z, L"zfg_zero_depth")) return fail("zero depth", 0);
  z.resourceDescription.format = FFX_SURFACE_FORMAT_R16G16_FLOAT;
  if (!im->Make(Impl::kZeroMv, z, L"zfg_zero_mv")) return fail("zero motion vectors", 0);

  return std::unique_ptr<Engine>(new Engine(std::move(im)));
}

bool Engine::Generate(VkCommandBuffer cmd, VkImage current, VkImage of_current, VkImage output, bool reset,
                      float frame_ms, bool extrapolate) {
  Impl* im = impl_.get();
  const uint32_t w = im->width, h = im->height;
  if (reset) im->have_prev = false;
  const bool first = !im->have_prev;
  FfxCommandList cl = ffxGetCommandListVK(cmd);
  // Sample this call's passes when due and the last sample has been read.
  const bool prof = im->prof_pool && im->prof_every > 0 && !im->prof_pending && ++im->prof_count >= im->prof_every &&
                    im->cmd_ts && im->cmd_reset && im->get_results;
  if (prof) {
    im->prof_count = 0;
    im->prof_labels.clear();
    im->prof_cmd = cmd;
    im->cmd_reset(cmd, im->prof_pool, 0, Impl::kMaxStamps);
    im->cmd_ts(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, im->prof_pool, 0);
    Impl::recording = im;
    zerofg_ffx_job_hook = &Impl::Hook;
  }
  struct Unhook {
    bool on;
    ~Unhook() {
      if (on) {
        zerofg_ffx_job_hook = nullptr;
        Impl::recording = nullptr;
      }
    }
  } unhook{prof};
  if (prof) im->prof_pending = true;
  FfxResource cur = Res(current, w, h, im->format, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        FFX_RESOURCE_STATE_COMPUTE_READ, L"zfg_current");
  FfxResource of_cur = of_current == current ? cur
                                             : Res(of_current, im->of_width, im->of_height, im->format,
                                                   VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                                                   FFX_RESOURCE_STATE_COMPUTE_READ, L"zfg_of_current");
  FfxResource out = Res(output, w, h, im->format, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        FFX_RESOURCE_STATE_UNORDERED_ACCESS, L"zfg_output");

  // A frameID that does not step by one resets FI, so reset by skipping one.
  if (first) ++im->frame_id;
  ++im->frame_id;
  im->toggle ^= 1;
  const int dd = im->toggle ? Impl::kDilDepth1 : Impl::kDilDepth0;
  const int dm = im->toggle ? Impl::kDilMv1 : Impl::kDilMv0;
  const int rd = im->toggle ? Impl::kRecDepth1 : Impl::kRecDepth0;

  FfxFrameInterpolationPrepareDescription p{};
  p.commandList = cl;
  p.renderSize = {w, h};
  p.motionVectorScale = {1.f, 1.f};
  p.frameTimeDelta = frame_ms;
  p.cameraNear = 0.1f;
  p.cameraFar = 1000.f;
  p.viewSpaceToMetersFactor = 1.f;
  p.cameraFovAngleVertical = 1.0f;
  p.depth = im->Get(Impl::kZeroDepth);
  p.motionVectors = im->Get(Impl::kZeroMv);
  p.frameID = im->frame_id;
  p.dilatedDepth = im->Get(dd);
  p.dilatedMotionVectors = im->Get(dm);
  p.reconstructedPrevDepth = im->Get(rd);
  if (ffxFrameInterpolationPrepare(&im->fi, &p) != FFX_OK) return false;

  FfxOpticalflowDispatchDescription o{};
  o.commandList = cl;
  o.color = of_cur;
  o.opticalFlowVector = im->Get(Impl::kOfVector);
  o.opticalFlowSCD = im->Get(Impl::kOfScd);
  o.reset = first;
  o.backbufferTransferFunction = FFX_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
  o.minMaxLuminance = {0.f, 1.f};
  if (ffxOpticalflowContextDispatch(&im->of, &o) != FFX_OK) return false;

  FfxFrameInterpolationDispatchDescription f{};
  f.commandList = cl;
  f.displaySize = {w, h};
  f.renderSize = {w, h};
  f.currentBackBuffer = cur;
  f.output = out;
  f.interpolationRect = {0, 0, int32_t(w), int32_t(h)};
  f.opticalFlowVector = o.opticalFlowVector;
  f.opticalFlowSceneChangeDetection = o.opticalFlowSCD;
  // The flow comes from the smaller copy: its blocks and pixel units are that size.
  f.opticalFlowBufferSize = {im->of_width / 8, im->of_height / 8};
  f.opticalFlowScale = {1.f / float(im->of_width), 1.f / float(im->of_height)};
  f.opticalFlowBlockSize = 8;
  f.cameraNear = p.cameraNear;
  f.cameraFar = p.cameraFar;
  f.cameraFovAngleVertical = p.cameraFovAngleVertical;
  f.viewSpaceToMetersFactor = 1.f;
  // The FI shaders read no frame time; our patch takes a negative one as "extrapolate".
  f.frameTimeDelta = extrapolate ? -std::max(frame_ms, 0.001f) : std::max(frame_ms, 0.001f);
  f.reset = first;
  f.backBufferTransferFunction = FFX_BACKBUFFER_TRANSFER_FUNCTION_SRGB;
  f.minMaxLuminance[0] = 0.f;
  f.minMaxLuminance[1] = 1.f;
  f.frameID = im->frame_id;
  f.dilatedDepth = p.dilatedDepth;
  f.dilatedMotionVectors = p.dilatedMotionVectors;
  f.reconstructedPrevDepth = p.reconstructedPrevDepth;
  if (ffxFrameInterpolationDispatch(&im->fi, &f) != FFX_OK) return false;
  im->have_prev = true;
  return true;
}

void Engine::Profile(float ts_period, int every) {
  impl_->ts_period = ts_period;
  impl_->prof_every = every;
}

std::string Engine::TakeProfile() {
  Impl* im = impl_.get();
  if (!im->prof_pending) return "";
  const uint32_t n = uint32_t(im->prof_labels.size()) + 1;
  if (n < 2) {
    im->prof_pending = false;
    return "";
  }
  std::vector<uint64_t> t(n);
  if (im->get_results(im->device, im->prof_pool, 0, n, n * sizeof(uint64_t), t.data(), sizeof(uint64_t),
                      VK_QUERY_RESULT_64_BIT) != VK_SUCCESS)
    return "";  // not done yet
  im->prof_pending = false;
  // Sum by pass name (OF runs its passes once per pyramid level), in first-seen order.
  std::vector<std::pair<std::string, double>> sums;
  double total = 0;
  for (uint32_t i = 1; i < n; ++i) {
    const double ms = t[i] > t[i - 1] ? double(t[i] - t[i - 1]) * im->ts_period / 1e6 : 0;
    total += ms;
    std::string name = im->prof_labels[i - 1];
    auto it = sums.begin();
    for (; it != sums.end() && it->first != name; ++it) {
    }
    if (it == sums.end())
      sums.emplace_back(name, ms);
    else
      it->second += ms;
  }
  std::string out;
  char buf[96];
  snprintf(buf, sizeof buf, "%.2f ms:", total);
  out = buf;
  for (auto& [name, ms] : sums) {
    snprintf(buf, sizeof buf, " %s %.2f", name.c_str(), ms);
    out += buf;
  }
  return out;
}

}  // namespace amdfg
