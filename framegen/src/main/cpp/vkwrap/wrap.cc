// libzfg_vk.so: handed to a game running inside the NewBlackbox engine in place
// of libvulkan.so. Every export forwards to the real loader (trampolines.S)
// except the few below, which add frame generation at present time: each game
// frame is copied out of its swapchain image, ZeroFG makes the frame between it
// and the previous one, and that frame is presented first, on the game's own
// device and queue.
#define VK_NO_PROTOTYPES
#define VK_USE_PLATFORM_ANDROID_KHR
#include <vulkan/vulkan.h>

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <time.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

#include "amd_fg.h"
#include "lsfg/lsfg.h"
#include "zerofg/zerofg.h"

#define BBW_EXPORT extern "C" __attribute__((visibility("default")))

extern bool zerofg_ffx_fp16;  // vendored ffx_vk.cpp: use FFX's 16-bit shaders

namespace {

constexpr const char* kTag = "zerofg-vk";

std::mutex g_log_m;
FILE* g_log = nullptr;

void Log(int prio, const char* fmt, ...) {
  char buf[512];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof buf, fmt, ap);
  va_end(ap);
  __android_log_write(prio, kTag, buf);
  std::lock_guard<std::mutex> lk(g_log_m);
  if (!g_log) return;
  time_t t = time(nullptr);
  struct tm tm;
  localtime_r(&t, &tm);
  fprintf(g_log, "%02d:%02d:%02d %c %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
          prio >= ANDROID_LOG_ERROR ? 'E' : prio >= ANDROID_LOG_WARN ? 'W' : 'I', buf);
  fflush(g_log);
}
#define LOGI(...) Log(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGW(...) Log(ANDROID_LOG_WARN, __VA_ARGS__)

int64_t NowNs() {
  timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return int64_t(ts.tv_sec) * 1'000'000'000 + ts.tv_nsec;
}

// ---------------------------------------------------------------- real entry points

PFN_vkGetInstanceProcAddr real_gipa = nullptr;
PFN_vkGetDeviceProcAddr real_gdpa = nullptr;
PFN_vkCreateInstance real_create_instance = nullptr;
PFN_vkEnumerateInstanceVersion real_enum_version = nullptr;

bool g_enabled = true;

struct Instance {
  VkInstance handle = VK_NULL_HANDLE;
  uint32_t api = VK_API_VERSION_1_0;
  PFN_vkCreateDevice create_device = nullptr;
  PFN_vkGetPhysicalDeviceProperties props = nullptr;
  PFN_vkGetPhysicalDeviceProperties2 props2 = nullptr;
  PFN_vkGetPhysicalDeviceFeatures2 features2 = nullptr;
  PFN_vkGetPhysicalDeviceQueueFamilyProperties queue_families = nullptr;
  PFN_vkGetPhysicalDeviceFormatProperties format_props = nullptr;
  PFN_vkGetPhysicalDeviceMemoryProperties mem_props = nullptr;
  PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR surface_caps = nullptr;
  PFN_vkEnumerateDeviceExtensionProperties device_exts = nullptr;
};
Instance g_inst;  // games use one instance; the last one created wins

struct Device;
struct Swap;

struct Device {
  VkDevice handle = VK_NULL_HANDLE;
  VkPhysicalDevice phys = VK_NULL_HANDLE;
  uint32_t api = VK_API_VERSION_1_0;
  bool capable = false;  // ZeroFG's features were enabled
  bool display_timing = false;
  // A compute-only queue of our own for the AMD engine, so it overlaps the game's
  // rendering instead of waiting behind it (async compute).
  uint32_t cq_family = UINT32_MAX, cq_index = 0;
  VkQueue cq = VK_NULL_HANDLE;
  std::vector<VkQueueFamilyProperties> families;
  VkPhysicalDeviceSubgroupProperties subgroup{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_PROPERTIES};
  VkPhysicalDeviceSubgroupSizeControlProperties subgroup_size{
      VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SUBGROUP_SIZE_CONTROL_PROPERTIES};

#define DEV_FN(name) PFN_vk##name name = nullptr;
#define DEV_FNS(X)                                                                        \
  X(DestroyDevice) X(GetDeviceQueue) X(GetDeviceQueue2) X(QueueSubmit) X(QueuePresentKHR)   \
  X(CreateSwapchainKHR) X(DestroySwapchainKHR) X(GetSwapchainImagesKHR)                  \
  X(AcquireNextImageKHR) X(CreateCommandPool) X(DestroyCommandPool)                      \
  X(AllocateCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer)                    \
  X(ResetCommandBuffer) X(CmdPipelineBarrier) X(CmdCopyImage) X(CreateFence)            \
  X(DestroyFence) X(WaitForFences) X(ResetFences) X(CreateSemaphore) X(DestroySemaphore)  \
  X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(AllocateMemory)         \
  X(FreeMemory) X(BindImageMemory) X(CreateImageView) X(DestroyImageView)                \
  X(DeviceWaitIdle) X(GetRefreshCycleDurationGOOGLE) X(CreateQueryPool) X(DestroyQueryPool) \
  X(CmdResetQueryPool) X(CmdWriteTimestamp) X(GetQueryPoolResults) X(CmdBlitImage) \
  X(GetPastPresentationTimingGOOGLE)
  DEV_FNS(DEV_FN)
#undef DEV_FN
};

struct QueueInfo {
  Device* dev;
  uint32_t family;
};

std::mutex g_m;  // guards the maps and every Swap
std::unordered_map<VkDevice, std::unique_ptr<Device>> g_devices;
std::unordered_map<VkQueue, QueueInfo> g_queues;
std::unordered_map<VkSwapchainKHR, std::unique_ptr<Swap>> g_swaps;
std::unordered_map<VkSurfaceKHR, ANativeWindow*> g_windows;

// ---------------------------------------------------------------- frame generation

struct Tex {
  VkImage image = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
};

VkFormat UnormOf(VkFormat f) {
  switch (f) {
    case VK_FORMAT_R8G8B8A8_SRGB: return VK_FORMAT_R8G8B8A8_UNORM;
    case VK_FORMAT_B8G8R8A8_SRGB: return VK_FORMAT_B8G8R8A8_UNORM;
    default: return f;
  }
}

constexpr int kSlots = 2;  // kSlots * (kMaxMult - 1) descriptor copies; ZeroFG allows 8
constexpr int kMaxMult = 4;  // outputs per game frame
static_assert(kSlots == 2, "low latency mode takes the other slot as the previous frame");
static_assert(kSlots * (kMaxMult - 1) <= 8, "ZeroFG takes at most 8 frame contexts");
int g_max_mult = kMaxMult;  // from settings
int g_target = 120;         // output fps the user wants (target=); paces and decides generation
int g_scale = 50;           // AMD engine resolution, % per axis (scale=)
// AMD engine on its own compute queue (async=1). Off by default: on the phone it
// cost the game ~10 fps (48 vs 60 at 67%) and FI time rose from 6 to 9.5 ms.
bool g_async = false;
bool g_pace = true;         // time presents with display timing (pace=0 turns off)
bool g_zero = false;        // Zero engine (2x only)
bool g_amd = false;         // AMD FidelityFX OF + FI, AFMF style (2x only)
bool g_extra = false;       // AMD engine extrapolates (extra=1): no added latency
// LSFG engine (engine=lsfg): Lossless Scaling's shaders from the user's own
// Lossless.dll (lsfg= names the pack the app made from it), 2x .. 4x (mult=).
bool g_lsfg = false;
// Low latency (lowlat=1): a present returns only once the GPU has finished the
// game's previous frame, so the game queues at most one frame ahead of the GPU
// (like "max pre-rendered frames = 1"). Costs a little fps on GPU-bound games.
bool g_lowlat = false;
bool g_edge = true;  // LSFG: our own final pass with the screen edge fix (edge=0 turns off)
lsfg::ShaderPack g_pack;
std::set<uint32_t> g_lsfg_caps;
bool LsfgCap(uint32_t c) { return g_lsfg_caps.count(c) != 0; }

struct Swap {
  Device* dev = nullptr;
  VkSwapchainKHR handle = VK_NULL_HANDLE;
  ANativeWindow* window = nullptr;
  VkFormat format = VK_FORMAT_UNDEFINED;
  VkFormat real_format = VK_FORMAT_UNDEFINED;
  VkExtent2D extent{};
  std::vector<VkImage> images;
  bool usable = false;  // the swapchain allows copies both ways
  bool failed = false;
  bool ready = false;
  uint32_t family = UINT32_MAX;

  VkCommandPool pool = VK_NULL_HANDLE;
  VkCommandBuffer cmd_a[kSlots]{}, cmd_b[kSlots * (kMaxMult - 1)]{};
  VkFence fence[kSlots]{};
  VkSemaphore sem_r[kSlots]{}, sem_g[kSlots]{};
  VkSemaphore sem_acq[kSlots * (kMaxMult - 1)]{}, sem_s[kSlots * (kMaxMult - 1)]{};
  int slot = 0;
  bool real_init[2] = {false, false};
  Tex real[2], gen;
  VkImageUsageFlags real_usage = 0, gen_usage = 0;
  std::unique_ptr<zerofg::Interpolator> fg;
  std::unique_ptr<amdfg::Engine> amd;
  std::unique_ptr<lsfg::Engine> lsfg;

  uint64_t seq = 0;
  uint64_t real_seq[2] = {0, 0};
  bool prev_valid = false;
  int64_t last_present = 0;
  double interval_ns = 0;
  bool times_logged = false;
  uint64_t paced_until = 0;  // refresh (vsync time) the last paced present is meant for
  uint64_t vsync_ref = 0;    // a time the screen really showed a frame at: the refresh grid
  // What reached the screen: our presents' ids and their actual times.
  uint32_t present_id = 0;
  uint64_t last_shown = 0;
  int stats_shown = 0, stats_hitch = 0;
  int lead = 2;  // refreshes between a game present and its generated frame's time
  uint64_t refresh_ns = 8'333'333;

  int64_t stats_start = 0;
  int stats_in = 0, stats_gen = 0, stats_skip = 0, stats_mult = 1;
  // GPU time of the AMD engine's work (OF + FI), from timestamps around it.
  VkQueryPool qpool = VK_NULL_HANDLE;
  // The AMD engine at a lower resolution: game frame scaled into amd_in, its
  // output amd_out scaled back up into gen.
  VkExtent2D amd_extent{};
  Tex amd_in, amd_out;
  // Async: per-slot input (the compute queue may still read the last one), its
  // command buffers and fences, and the semaphore from the copy to the compute work.
  bool async = false;
  Tex amd_in2;
  VkCommandPool cpool = VK_NULL_HANDLE;
  VkCommandBuffer cmd_c[kSlots]{};
  VkFence fence_c[kSlots]{};
  VkSemaphore sem_x[kSlots]{};
  int stats_late = 0, clean_secs = 0;
  double ts_period = 0;
  bool q_written[kSlots]{};
  double gpu_sum = 0, gpu_max = 0;
  int gpu_n = 0;
};

uint32_t FindMemory(Device* d, uint32_t bits, VkMemoryPropertyFlags want) {
  VkPhysicalDeviceMemoryProperties mp;
  g_inst.mem_props(d->phys, &mp);
  for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
    if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
  for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
    if (bits & (1u << i)) return i;
  return UINT32_MAX;
}

bool MakeTex(Device* d, VkExtent2D e, VkFormat f, VkImageUsageFlags usage, Tex* t, const uint32_t* families = nullptr) {
  VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  if (families && families[0] != families[1]) {
    ci.sharingMode = VK_SHARING_MODE_CONCURRENT;
    ci.queueFamilyIndexCount = 2;
    ci.pQueueFamilyIndices = families;
  }
  ci.imageType = VK_IMAGE_TYPE_2D;
  ci.format = f;
  ci.extent = {e.width, e.height, 1};
  ci.mipLevels = 1;
  ci.arrayLayers = 1;
  ci.samples = VK_SAMPLE_COUNT_1_BIT;
  ci.tiling = VK_IMAGE_TILING_OPTIMAL;
  ci.usage = usage;
  ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (d->CreateImage(d->handle, &ci, nullptr, &t->image) != VK_SUCCESS) return false;
  VkMemoryRequirements req;
  d->GetImageMemoryRequirements(d->handle, t->image, &req);
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  ai.allocationSize = req.size;
  ai.memoryTypeIndex = FindMemory(d, req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (ai.memoryTypeIndex == UINT32_MAX ||
      d->AllocateMemory(d->handle, &ai, nullptr, &t->memory) != VK_SUCCESS)
    return false;
  if (d->BindImageMemory(d->handle, t->image, t->memory, 0) != VK_SUCCESS) return false;
  VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  vi.image = t->image;
  vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
  vi.format = f;
  vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  return d->CreateImageView(d->handle, &vi, nullptr, &t->view) == VK_SUCCESS;
}

void FreeTex(Device* d, Tex* t) {
  if (t->view) d->DestroyImageView(d->handle, t->view, nullptr);
  if (t->image) d->DestroyImage(d->handle, t->image, nullptr);
  if (t->memory) d->FreeMemory(d->handle, t->memory, nullptr);
  *t = Tex{};
}

void Barrier(Device* d, VkCommandBuffer c, VkImage img, VkImageLayout from, VkImageLayout to,
             VkPipelineStageFlags src_stage, VkAccessFlags src, VkPipelineStageFlags dst_stage,
             VkAccessFlags dst) {
  VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  b.srcAccessMask = src;
  b.dstAccessMask = dst;
  b.oldLayout = from;
  b.newLayout = to;
  b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.image = img;
  b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  d->CmdPipelineBarrier(c, src_stage, dst_stage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void Copy(Device* d, VkCommandBuffer c, VkImage from, VkImage to, VkExtent2D e) {
  VkImageCopy r{};
  r.srcSubresource = r.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
  r.extent = {e.width, e.height, 1};
  d->CmdCopyImage(c, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to,
                  VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
}

void DestroySwapState(Swap* s) {
  Device* d = s->dev;
  if (s->ready || s->pool) d->DeviceWaitIdle(d->handle);
  s->fg.reset();
  s->amd.reset();
  s->lsfg.reset();
  for (int i = 0; i < kSlots; ++i) {
    if (s->fence[i]) d->DestroyFence(d->handle, s->fence[i], nullptr);
    for (VkSemaphore* sem : {&s->sem_r[i], &s->sem_g[i]})
      if (*sem) d->DestroySemaphore(d->handle, *sem, nullptr);
  }
  for (int i = 0; i < kSlots * (kMaxMult - 1); ++i)
    for (VkSemaphore* sem : {&s->sem_acq[i], &s->sem_s[i]})
      if (*sem) d->DestroySemaphore(d->handle, *sem, nullptr);
  if (s->pool) d->DestroyCommandPool(d->handle, s->pool, nullptr);
  if (s->qpool) d->DestroyQueryPool(d->handle, s->qpool, nullptr);
  FreeTex(d, &s->real[0]);
  FreeTex(d, &s->real[1]);
  FreeTex(d, &s->gen);
  FreeTex(d, &s->amd_in);
  FreeTex(d, &s->amd_in2);
  FreeTex(d, &s->amd_out);
  for (int i = 0; i < kSlots; ++i) {
    if (s->fence_c[i]) d->DestroyFence(d->handle, s->fence_c[i], nullptr);
    if (s->sem_x[i]) d->DestroySemaphore(d->handle, s->sem_x[i], nullptr);
  }
  if (s->cpool) d->DestroyCommandPool(d->handle, s->cpool, nullptr);
}

bool HasFormatFeatures(Device* d, VkFormat f, VkFormatFeatureFlags want) {
  VkFormatProperties fp;
  g_inst.format_props(d->phys, f, &fp);
  return (fp.optimalTilingFeatures & want) == want;
}

// Lazily, on the first present: images, command buffers and the interpolator.
bool InitSwap(Swap* s, uint32_t family) {
  Device* d = s->dev;
  s->family = family;
  s->real_format = UnormOf(s->format);
  if (!HasFormatFeatures(d, s->real_format,
                         VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT |
                             VK_FORMAT_FEATURE_TRANSFER_DST_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT)) {
    LOGW("format %d cannot be sampled/blitted; frame generation off", s->real_format);
    return false;
  }
  s->real_usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  s->gen_usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
  if ((g_amd || g_lsfg || s->real_format == VK_FORMAT_A2B10G10R10_UNORM_PACK32) &&
      HasFormatFeatures(d, s->real_format, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT))
    s->gen_usage |= VK_IMAGE_USAGE_STORAGE_BIT;
  if ((g_amd || g_lsfg) && !(s->gen_usage & VK_IMAGE_USAGE_STORAGE_BIT)) {
    LOGW("format %d is no storage image; the engine needs one", s->real_format);
    return false;
  }
  if (!MakeTex(d, s->extent, s->real_format, s->real_usage, &s->real[0]) ||
      !MakeTex(d, s->extent, s->real_format, s->real_usage, &s->real[1]) ||
      !MakeTex(d, s->extent, s->real_format, s->gen_usage, &s->gen)) {
    LOGW("image allocation failed");
    return false;
  }

  VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
  pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
  pci.queueFamilyIndex = family;
  if (d->CreateCommandPool(d->handle, &pci, nullptr, &s->pool) != VK_SUCCESS) return false;
  VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
  cai.commandPool = s->pool;
  cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
  cai.commandBufferCount = kSlots;
  if (d->AllocateCommandBuffers(d->handle, &cai, s->cmd_a) != VK_SUCCESS ||
      [&] {
        cai.commandBufferCount = kSlots * (kMaxMult - 1);
        return d->AllocateCommandBuffers(d->handle, &cai, s->cmd_b);
      }() != VK_SUCCESS)
    return false;
  const uint32_t ts_family = g_amd && g_async && d->cq ? d->cq_family : family;
  if (g_amd && ts_family < d->families.size() && d->families[ts_family].timestampValidBits && d->CreateQueryPool) {
    VkPhysicalDeviceProperties pp{};
    g_inst.props(d->phys, &pp);
    VkQueryPoolCreateInfo qci{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qci.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qci.queryCount = 2 * kSlots;
    if (pp.limits.timestampPeriod > 0 && d->CreateQueryPool(d->handle, &qci, nullptr, &s->qpool) == VK_SUCCESS)
      s->ts_period = pp.limits.timestampPeriod;
  }
  // Dispatchable handles made through the real loader need its dispatch pointer.
  VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
  fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
  VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  for (int i = 0; i < kSlots; ++i) {
    if (d->CreateFence(d->handle, &fci, nullptr, &s->fence[i]) != VK_SUCCESS) return false;
    for (VkSemaphore* sem : {&s->sem_r[i], &s->sem_g[i]})
      if (d->CreateSemaphore(d->handle, &sci, nullptr, sem) != VK_SUCCESS) return false;
  }
  for (int i = 0; i < kSlots * (kMaxMult - 1); ++i)
    for (VkSemaphore* sem : {&s->sem_acq[i], &s->sem_s[i]})
      if (d->CreateSemaphore(d->handle, &sci, nullptr, sem) != VK_SUCCESS) return false;

  if (g_lsfg) {
    std::string err;
    VkPhysicalDeviceMemoryProperties mem{};
    g_inst.mem_props(d->phys, &mem);
    const VkImageView frames[2] = {s->real[0].view, s->real[1].view};
    s->lsfg = lsfg::Engine::Create(d->phys, d->handle, real_gdpa, mem, g_pack, s->extent.width, s->extent.height,
                                   frames, s->gen.view, float(g_scale) / 100.f,
                                   g_edge, &err);
    if (!s->lsfg) {
      LOGW("LSFG engine: %s", err.c_str());
      return false;
    }
    if (d->display_timing && d->GetRefreshCycleDurationGOOGLE) {
      VkRefreshCycleDurationGOOGLE rc{};
      if (d->GetRefreshCycleDurationGOOGLE(d->handle, s->handle, &rc) == VK_SUCCESS && rc.refreshDuration)
        s->refresh_ns = rc.refreshDuration;
    }
    LOGI("LSFG ready (up to %dx, flow at %d%%, edge fix %s): %ux%u format %d, refresh %.2f ms", g_max_mult,
         g_scale, g_edge ? "on" : "off", s->extent.width, s->extent.height, s->format, s->refresh_ns / 1e6);
    return true;
  }
  if (g_amd) {
    std::string err;
    s->amd_extent = s->extent;
    const VkFormatFeatureFlags blit =
        VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    if (g_scale < 100 && HasFormatFeatures(d, s->real_format, blit)) {
      const VkExtent2D e{std::max(64u, (s->extent.width * g_scale / 100) & ~1u),
                         std::max(64u, (s->extent.height * g_scale / 100) & ~1u)};
      // Async compute is retired: FI now reads the full-size game frame, which
      // lives on the game's queue. (It also cost the game ~10 fps on the phone.)
      const bool async = false;
      const uint32_t fam[2] = {family, async ? d->cq_family : family};
      const VkImageUsageFlags in_usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
      if (MakeTex(d, e, s->real_format, in_usage, &s->amd_in, fam) &&
          (!async || MakeTex(d, e, s->real_format, in_usage, &s->amd_in2, fam)) &&
          (!async || MakeTex(d, e, s->real_format, s->gen_usage, &s->amd_out, fam))) {
        s->async = async;
        s->amd_extent = e;
      } else {
        FreeTex(d, &s->amd_in);
        FreeTex(d, &s->amd_in2);
        FreeTex(d, &s->amd_out);
      }
    }
    if (s->async) {
      VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
      cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
      cpi.queueFamilyIndex = d->cq_family;
      VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
      cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
      cai.commandBufferCount = kSlots;
      VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
      fci.flags = VK_FENCE_CREATE_SIGNALED_BIT;
      VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
      bool ok = d->CreateCommandPool(d->handle, &cpi, nullptr, &s->cpool) == VK_SUCCESS;
      cai.commandPool = s->cpool;
      ok = ok && d->AllocateCommandBuffers(d->handle, &cai, s->cmd_c) == VK_SUCCESS;
      for (int i = 0; ok && i < kSlots; ++i)
        ok = d->CreateFence(d->handle, &fci, nullptr, &s->fence_c[i]) == VK_SUCCESS &&
             d->CreateSemaphore(d->handle, &sci, nullptr, &s->sem_x[i]) == VK_SUCCESS;
      if (!ok) {
        LOGW("async compute setup failed; the AMD engine runs on the game's queue");
        s->async = false;
      }
    }
    // The optical flow runs on the smaller copy (scale=); the generated frame is
    // made at full size from the full-size frames, as Lossless Scaling does.
    const bool small = s->amd_in.image != VK_NULL_HANDLE;
    s->amd = amdfg::Engine::Create(d->phys, d->handle, real_gdpa, s->extent.width, s->extent.height, s->real_format,
                                   &err, small ? s->amd_extent.width : 0, small ? s->amd_extent.height : 0);
    if (!s->amd) {
      LOGW("AMD FidelityFX engine: %s", err.c_str());
      return false;
    }
    // GPU time per FFX pass, sampled every 600 frames (~10 s), for the log.
    if (s->qpool) s->amd->Profile(float(s->ts_period), 600);
    if (d->display_timing && d->GetRefreshCycleDurationGOOGLE) {
      VkRefreshCycleDurationGOOGLE rc{};
      if (d->GetRefreshCycleDurationGOOGLE(d->handle, s->handle, &rc) == VK_SUCCESS && rc.refreshDuration)
        s->refresh_ns = rc.refreshDuration;
    }
    LOGI("AMD FidelityFX FSR 3.1 OF + FI ready (no game data, 2x, %s): %ux%u (works at %ux%u) format %d, refresh %.2f ms, %s",
         g_extra ? "extrapolating" : "interpolating", s->extent.width, s->extent.height, s->amd_extent.width,
         s->amd_extent.height, s->format, s->refresh_ns / 1e6, s->async ? "async compute" : "game's queue");
    return true;
  }
  zerofg::Capabilities caps;
  caps.effective_api_version = std::min(d->api, g_inst.api);
  caps.synchronization2_enabled = true;
  caps.shader_storage_image_extended_formats_enabled = true;
  caps.subgroup_size = d->subgroup.subgroupSize;
  caps.subgroup_min_size = d->subgroup_size.minSubgroupSize;
  caps.subgroup_max_size = d->subgroup_size.maxSubgroupSize;
  caps.max_compute_workgroup_subgroups = d->subgroup_size.maxComputeWorkgroupSubgroups;
  caps.subgroup_supported_stages = d->subgroup.supportedStages;
  caps.subgroup_supported_operations = d->subgroup.supportedOperations;
  for (zerofg::Backend backend : {zerofg::Backend::kAuto, zerofg::Backend::kCompat}) {
    zerofg::CreateInfo info;
    info.vulkan.instance = g_inst.handle;
    info.vulkan.physical_device = d->phys;
    info.vulkan.device = d->handle;
    info.vulkan.get_instance_proc_addr = real_gipa;
    info.mode = g_zero ? zerofg::Mode::kZero : zerofg::Mode::kReallyZero;
    info.backend = backend;
    info.capabilities = caps;
    info.frame_context_count = kSlots * (kMaxMult - 1);  // one descriptor copy per call in flight
    zerofg::Status st;
    auto fg = zerofg::Interpolator::Create(info, &st);
    if (!fg) {
      LOGW("ZeroFG create (backend %d) failed: %d", int(backend), int(st));
      continue;
    }
    st = fg->Resize(s->extent.width, s->extent.height, s->real_format, s->real_format);
    if (st != zerofg::Status::kSuccess) {
      LOGW("ZeroFG resize (backend %d) failed: %d", int(backend), int(st));
      continue;
    }
    s->fg = std::move(fg);
    break;
  }
  if (!s->fg) return false;
  if (d->display_timing && d->GetRefreshCycleDurationGOOGLE) {
    VkRefreshCycleDurationGOOGLE rc{};
    if (d->GetRefreshCycleDurationGOOGLE(d->handle, s->handle, &rc) == VK_SUCCESS && rc.refreshDuration)
      s->refresh_ns = rc.refreshDuration;
  }
  LOGI("ZeroFG ready (%s, max %dx): %ux%u format %d, refresh %.2f ms", g_zero ? "One" : "Zero", g_max_mult, s->extent.width, s->extent.height,
       s->format, s->refresh_ns / 1e6);
  return true;
}

zerofg::Image Wrap(const Swap* s, const Tex& t, VkImageLayout layout, VkImageUsageFlags usage, uint64_t seq) {
  zerofg::Image im;
  im.image = t.image;
  im.view = t.view;
  im.layout = layout;
  im.format = s->real_format;
  im.width = s->extent.width;
  im.height = s->extent.height;
  im.usage = usage;
  im.tiling = VK_IMAGE_TILING_OPTIMAL;
  im.creation_facts_known = true;
  im.sequence = seq;
  return im;
}

// The output frame interval: the user's target, not the panel's current rate
// (an adaptive panel follows the content, so it would chase our own output).
uint64_t FrameNs() { return uint64_t(1e9 / double(g_target)); }

// One game present: R copied out, S made and presented first, then R.
VkResult FgPresent(Swap* s, VkQueue queue, const VkPresentInfoKHR* pi) {
  Device* d = s->dev;
  const uint32_t idx = pi->pImageIndices[0];
  const int64_t now = NowNs();
  const int64_t dt = s->last_present ? now - s->last_present : 0;
  s->last_present = now;
  if (dt > 0 && dt < 100'000'000)
    s->interval_ns = s->interval_ns > 0 ? s->interval_ns * 0.8 + double(dt) * 0.2 : double(dt);
  if (!s->stats_start) s->stats_start = now;
  ++s->stats_in;

  const int slot = s->slot;
  s->slot = (s->slot + 1) % kSlots;
  d->WaitForFences(d->handle, 1, &s->fence[slot], VK_TRUE, 1'000'000'000);
  d->ResetFences(d->handle, 1, &s->fence[slot]);
  if (s->async) {
    d->WaitForFences(d->handle, 1, &s->fence_c[slot], VK_TRUE, 1'000'000'000);
    d->ResetFences(d->handle, 1, &s->fence_c[slot]);
  }
  if (s->qpool && s->q_written[slot]) {
    uint64_t ts[2] = {};
    if (d->GetQueryPoolResults(d->handle, s->qpool, uint32_t(slot) * 2, 2, sizeof ts, ts, sizeof(uint64_t),
                               VK_QUERY_RESULT_64_BIT) == VK_SUCCESS && ts[1] > ts[0]) {
      const double ms = double(ts[1] - ts[0]) * s->ts_period / 1e6;
      s->gpu_sum += ms;
      s->gpu_max = std::max(s->gpu_max, ms);
      ++s->gpu_n;
    }
    s->q_written[slot] = false;
  }

  const int cur = int(s->seq & 1), prev = cur ^ 1;
  ++s->seq;
  s->real_seq[cur] = s->seq;
  // A screen with no room for another frame (a game near the refresh rate), or a
  // stall, gets no generated frame. Otherwise one per spare refresh: 60 fps on
  // 120 Hz gets 1, 40 gets 2, 30 gets 3.
  const bool gen = s->prev_valid && dt > 0 && dt < 70'000'000 &&
                   s->interval_ns > 1.4 * double(FrameNs());
  const int k = !gen ? 1 : g_amd ? 2 : std::clamp(int(s->interval_ns / double(FrameNs()) + 0.25), 2, g_max_mult);

  VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
  // gen_ is written again only after the last copy (an earlier submission) read it.
  auto interpolate = [&](VkCommandBuffer c, float phase) {
    Barrier(d, c, s->gen.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
    if (s->lsfg) {
      if (!s->lsfg->Ready()) return false;
      s->lsfg->Generate(c, cur, k - 1, int(phase * float(k) + 0.5f) - 1);
      return true;
    }
    if (s->amd) {
      // FI keeps the previous frame itself and makes only the midpoint.
      const bool reset = !s->prev_valid || dt <= 0 || dt >= 70'000'000;
      bool ok;
      if (s->async) {
        // Graphics queue (this command buffer): game frame -> this slot's input.
        // Compute queue (cmd_c): OF + FI -> amd_out, left in TRANSFER_SRC for the
        // generated frame's command buffer to scale up.
        const Tex& in = slot ? s->amd_in2 : s->amd_in;
        const VkPipelineStageFlags all = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        VkImageBlit r{};
        r.srcSubresource = r.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.srcOffsets[1] = {int32_t(s->extent.width), int32_t(s->extent.height), 1};
        r.dstOffsets[1] = {int32_t(s->amd_extent.width), int32_t(s->amd_extent.height), 1};
        Barrier(d, c, s->real[cur].image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                all, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        Barrier(d, c, in.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, all, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        d->CmdBlitImage(c, s->real[cur].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, in.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_LINEAR);
        Barrier(d, c, s->real[cur].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, all, VK_ACCESS_SHADER_READ_BIT);
        Barrier(d, c, in.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, all, VK_ACCESS_SHADER_READ_BIT);
        VkCommandBuffer cc = s->cmd_c[slot];
        d->ResetCommandBuffer(cc, 0);
        d->BeginCommandBuffer(cc, &bi);
        if (s->qpool) {
          d->CmdResetQueryPool(cc, s->qpool, uint32_t(slot) * 2, 2);
          d->CmdWriteTimestamp(cc, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, s->qpool, uint32_t(slot) * 2);
        }
        Barrier(d, cc, s->amd_out.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, all, 0, all,
                VK_ACCESS_SHADER_WRITE_BIT);
        ok = s->amd->Generate(cc, in.image, in.image, s->amd_out.image, reset, float(dt) / 1e6f, g_extra);
        Barrier(d, cc, s->amd_out.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, all,
                VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        if (s->qpool) {
          d->CmdWriteTimestamp(cc, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s->qpool, uint32_t(slot) * 2 + 1);
          s->q_written[slot] = true;
        }
        d->EndCommandBuffer(cc);
      } else if (s->amd_in.image) {
        auto blit = [&](VkImage from, VkExtent2D fe, VkImage to, VkExtent2D te) {
          VkImageBlit r{};
          r.srcSubresource = r.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
          r.srcOffsets[1] = {int32_t(fe.width), int32_t(fe.height), 1};
          r.dstOffsets[1] = {int32_t(te.width), int32_t(te.height), 1};
          d->CmdBlitImage(c, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r,
                          VK_FILTER_LINEAR);
        };
        const VkPipelineStageFlags all = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        // Game frame -> amd_in.
        Barrier(d, c, s->real[cur].image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                all, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        Barrier(d, c, s->amd_in.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, all,
                VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        blit(s->real[cur].image, s->extent, s->amd_in.image, s->amd_extent);
        Barrier(d, c, s->real[cur].image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, all, VK_ACCESS_SHADER_READ_BIT);
        Barrier(d, c, s->amd_in.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, all, VK_ACCESS_SHADER_READ_BIT);
        // Flow from amd_in, the generated frame from the full-size frames into gen.
        ok = s->amd->Generate(c, s->real[cur].image, s->amd_in.image, s->gen.image, reset, float(dt) / 1e6f, g_extra);
      } else {
        ok = s->amd->Generate(c, s->real[cur].image, s->real[cur].image, s->gen.image, reset, float(dt) / 1e6f,
                              g_extra);
      }
      if (!ok) {
        static bool logged = false;
        if (!logged) LOGW("AMD FidelityFX dispatch failed");
        logged = true;
      }
      return ok && !reset;
    }
    zerofg::Status st = s->fg->Interpolate(
        c, uint32_t(slot),
        Wrap(s, s->real[prev], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, s->real_usage, s->real_seq[prev]),
        Wrap(s, s->real[cur], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, s->real_usage, s->real_seq[cur]), phase,
        Wrap(s, s->gen, VK_IMAGE_LAYOUT_GENERAL, s->gen_usage, 0));
    if (st != zerofg::Status::kSuccess) {
      static bool logged = false;
      if (!logged) LOGW("Interpolate (phase %.2f) failed: %d", phase, int(st));
      logged = true;
    }
    return st == zerofg::Status::kSuccess;
  };

  VkCommandBuffer a = s->cmd_a[slot];
  d->ResetCommandBuffer(a, 0);
  d->BeginCommandBuffer(a, &bi);
  Barrier(d, a, s->images[idx], VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
          VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
  Barrier(d, a, s->real[cur].image,
          s->real_init[cur] ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT,
          VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
  s->real_init[cur] = true;
  Copy(d, a, s->images[idx], s->real[cur].image, s->extent);
  Barrier(d, a, s->images[idx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
          VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
  Barrier(d, a, s->real[cur].image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
          VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
          VK_ACCESS_SHADER_READ_BIT);
  // The AMD engine sees every frame, shown or not: OF and FI keep the last one.
  if (s->qpool && !s->async) {
    d->CmdResetQueryPool(a, s->qpool, uint32_t(slot) * 2, 2);
    d->CmdWriteTimestamp(a, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, s->qpool, uint32_t(slot) * 2);
  }
  // LSFG keeps a history of frames: it sees every one, shown or not.
  if (s->lsfg) s->lsfg->Push(a, cur, !s->prev_valid || dt <= 0 || dt >= 70'000'000);
  const bool generated = s->amd ? interpolate(a, 0.5f) && gen : gen && interpolate(a, 1.f / float(k));
  if (s->qpool && !s->async) {
    d->CmdWriteTimestamp(a, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s->qpool, uint32_t(slot) * 2 + 1);
    s->q_written[slot] = true;
  }
  d->EndCommandBuffer(a);

  std::vector<VkPipelineStageFlags> stages(pi->waitSemaphoreCount, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
  // Async: the copy signals sem_x for the compute queue, which signals sem_g.
  VkSemaphore signal_a[2] = {s->sem_r[slot], s->async ? s->sem_x[slot] : s->sem_g[slot]};
  VkSubmitInfo sa{VK_STRUCTURE_TYPE_SUBMIT_INFO};
  sa.waitSemaphoreCount = pi->waitSemaphoreCount;
  sa.pWaitSemaphores = pi->pWaitSemaphores;
  sa.pWaitDstStageMask = stages.data();
  sa.commandBufferCount = 1;
  sa.pCommandBuffers = &a;
  sa.signalSemaphoreCount = generated || s->async ? 2 : 1;
  sa.pSignalSemaphores = signal_a;
  VkResult r = d->QueueSubmit(queue, 1, &sa, generated ? VK_NULL_HANDLE : s->fence[slot]);
  if (r != VK_SUCCESS) {
    LOGW("copy submit failed: %d", r);
    s->prev_valid = false;
    if (s->async) d->QueueSubmit(d->cq, 0, nullptr, s->fence_c[slot]);
    return d->QueuePresentKHR(queue, pi);
  }
  if (s->async) {
    // Always run: FI keeps the previous frame itself.
    const VkPipelineStageFlags cw = VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    VkSubmitInfo sc{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    sc.waitSemaphoreCount = 1;
    sc.pWaitSemaphores = &s->sem_x[slot];
    sc.pWaitDstStageMask = &cw;
    sc.commandBufferCount = 1;
    sc.pCommandBuffers = &s->cmd_c[slot];
    sc.signalSemaphoreCount = generated ? 1 : 0;
    sc.pSignalSemaphores = &s->sem_g[slot];
    if (d->QueueSubmit(d->cq, 1, &sc, s->fence_c[slot]) != VK_SUCCESS) {
      LOGW("compute submit failed; the AMD engine moves to the game's queue");
      s->async = false;
      // sem_x stays signalled with nobody waiting: drain it on the game's queue.
      VkSubmitInfo dr{VK_STRUCTURE_TYPE_SUBMIT_INFO};
      dr.waitSemaphoreCount = 1;
      dr.pWaitSemaphores = &s->sem_x[slot];
      dr.pWaitDstStageMask = &cw;
      d->QueueSubmit(queue, 1, &dr, VK_NULL_HANDLE);
      d->QueueSubmit(d->cq, 0, nullptr, s->fence_c[slot]);
      s->prev_valid = false;
      if (generated) {
        // No compute work will signal sem_g; present only the game frame.
        VkSubmitInfo sf{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        d->QueueSubmit(queue, 1, &sf, s->fence[slot]);
        VkPresentInfoKHR pr = *pi;
        pr.waitSemaphoreCount = 1;
        pr.pWaitSemaphores = &s->sem_r[slot];
        return d->QueuePresentKHR(queue, &pr);
      }
    }
  }

  // A game that paces itself (Swappy and the like) gives its frame a present time
  // through VK_GOOGLE_display_timing. SurfaceFlinger drops a timed buffer once a
  // later one is due, and an untimed present after it may inherit the stale time,
  // so each generated frame gets its own time: one refresh before the game's.
  const VkPresentTimesInfoGOOGLE* times = nullptr;
  for (auto* n = static_cast<const VkBaseInStructure*>(pi->pNext); n; n = n->pNext)
    if (n->sType == VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE)
      times = reinterpret_cast<const VkPresentTimesInfoGOOGLE*>(n);
  const uint64_t game_time = times && times->swapchainCount && times->pTimes ? times->pTimes[0].desiredPresentTime : 0;
  if (times && !s->times_logged) {
    s->times_logged = true;
    LOGI("game sets present times (display timing)%s", game_time ? "" : ", time 0");
  }

  // Without times, BLASTBufferQueue hands every queued buffer to SurfaceFlinger at
  // once, and of two buffers that land before the same refresh only the later is
  // shown: the generated frame, presented right before the game's, is dropped
  // whenever both are ready together. So the wrapper times its own presents: the
  // generated frames one refresh apart, the game's frame one refresh after them.
  const bool pace = g_pace && !times && d->display_timing;
  uint64_t t0 = 0;
  if (pace && generated) {
    // Every time is a refresh of the screen's own grid (from a frame it really
    // showed), and a present asks for half a refresh before it: SurfaceFlinger
    // shows a frame at the first refresh at or after its time, so a time at a random
    // phase came out up to a refresh late, which read as lateness and held lead at 3.
    const uint64_t r = FrameNs();
    const uint64_t v = s->refresh_ns;
    auto on_grid = [&](uint64_t t) {  // first refresh at or after t
      if (!s->vsync_ref || t <= s->vsync_ref) return t;
      return s->vsync_ref + (t - s->vsync_ref + v - 1) / v * v;
    };
    t0 = std::max<uint64_t>(on_grid(uint64_t(now) + uint64_t(s->lead) * r), s->paced_until + r);
    if (t0 > uint64_t(now) + uint64_t(s->lead + 3) * r) t0 = on_grid(uint64_t(now) + uint64_t(s->lead) * r);  // clock or rate change: start over
    s->paced_until = t0 + uint64_t(k - 1) * r;
    if (!s->times_logged) {
      s->times_logged = true;
      LOGI("pacing presents with display timing, %.2f ms apart", r / 1e6);
    }
  }

  // The game's own frame. Interpolating it goes after its generated frames (they
  // lie before it); extrapolating, before them (they lie after it).
  auto present_real = [&]() -> VkResult {
    VkPresentInfoKHR pr = *pi;
    // A paced swapchain times every game frame too: Surface keeps the last time set.
    VkPresentTimeGOOGLE rt{0, 0};
    VkPresentTimesInfoGOOGLE rtimes{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE};
    if (pace) {
      rt.presentID = ++s->present_id;
      // Extrapolating, the game frame comes first (t0) and its generated frames after.
      rt.desiredPresentTime =
          !generated ? uint64_t(now) : (g_extra ? t0 : s->paced_until) - s->refresh_ns / 2;
      if (!generated) s->paced_until = 0;
      rtimes.pNext = pi->pNext;
      rtimes.swapchainCount = 1;
      rtimes.pTimes = &rt;
      pr.pNext = &rtimes;
    }
    pr.waitSemaphoreCount = 1;
    pr.pWaitSemaphores = &s->sem_r[slot];
    return d->QueuePresentKHR(queue, &pr);
  };
  VkResult real_res = VK_SUCCESS;
  if (g_extra && generated) real_res = present_real();

  if (generated) {
    const VkPipelineStageFlags wait_stage[2] = {VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT};
    bool sem_g_pending = true;
    // S1 .. S(k-1), each copied into a swapchain image of its own and presented.
    for (int p = 0; p < k - 1; ++p) {
      const int e = slot * (kMaxMult - 1) + p;
      uint32_t j = 0;
      VkResult ar = d->AcquireNextImageKHR(d->handle, s->handle, 100'000'000, s->sem_acq[e], VK_NULL_HANDLE, &j);
      if (ar != VK_SUCCESS && ar != VK_SUBOPTIMAL_KHR) {
        ++s->stats_skip;
        break;
      }
      VkCommandBuffer b = s->cmd_b[e];
      d->ResetCommandBuffer(b, 0);
      d->BeginCommandBuffer(b, &bi);
      if (p > 0) interpolate(b, float(p + 1) / float(k));
      if (s->async && p == 0) {
        // The compute queue's output, scaled up into gen (sem_g waited above).
        VkImageBlit r{};
        r.srcSubresource = r.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        r.srcOffsets[1] = {int32_t(s->amd_extent.width), int32_t(s->amd_extent.height), 1};
        r.dstOffsets[1] = {int32_t(s->extent.width), int32_t(s->extent.height), 1};
        Barrier(d, b, s->gen.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
        d->CmdBlitImage(b, s->amd_out.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, s->gen.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_LINEAR);
        Barrier(d, b, s->gen.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT);
      }
      Barrier(d, b, s->gen.image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
              VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
      Barrier(d, b, s->images[j], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
              VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);
      Copy(d, b, s->gen.image, s->images[j], s->extent);
      Barrier(d, b, s->images[j], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
              VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0);
      Barrier(d, b, s->gen.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
              VK_PIPELINE_STAGE_TRANSFER_BIT, 0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0);
      d->EndCommandBuffer(b);
      VkSemaphore wait_b[2] = {s->sem_acq[e], s->sem_g[slot]};
      VkSubmitInfo sb{VK_STRUCTURE_TYPE_SUBMIT_INFO};
      sb.waitSemaphoreCount = p == 0 ? 2 : 1;
      sb.pWaitSemaphores = wait_b;
      sb.pWaitDstStageMask = wait_stage;
      sb.commandBufferCount = 1;
      sb.pCommandBuffers = &b;
      sb.signalSemaphoreCount = 1;
      sb.pSignalSemaphores = &s->sem_s[e];
      if (d->QueueSubmit(queue, 1, &sb, VK_NULL_HANDLE) != VK_SUCCESS) break;
      if (p == 0) sem_g_pending = false;
      VkPresentInfoKHR ps{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
      VkPresentTimeGOOGLE gt{0, 0};
      VkPresentTimesInfoGOOGLE gtimes{VK_STRUCTURE_TYPE_PRESENT_TIMES_INFO_GOOGLE};
      if (times || pace) {
        const uint64_t back = uint64_t(k - 1 - p) * FrameNs();
        if (pace) gt.presentID = ++s->present_id;
        const uint64_t at = t0 + uint64_t(p + (g_extra ? 1 : 0)) * FrameNs();
        gt.desiredPresentTime = pace ? at - s->refresh_ns / 2 : game_time > back ? game_time - back : 0;
        gtimes.swapchainCount = 1;
        gtimes.pTimes = &gt;
        ps.pNext = &gtimes;
      }
      ps.waitSemaphoreCount = 1;
      ps.pWaitSemaphores = &s->sem_s[e];
      ps.swapchainCount = 1;
      ps.pSwapchains = &s->handle;
      ps.pImageIndices = &j;
      d->QueuePresentKHR(queue, &ps);
      ++s->stats_gen;
    }
    // The slot's fence, after everything above; it also consumes sem_g if no S used it.
    VkSubmitInfo sf{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    sf.waitSemaphoreCount = sem_g_pending ? 1 : 0;
    sf.pWaitSemaphores = &s->sem_g[slot];
    sf.pWaitDstStageMask = wait_stage;
    d->QueueSubmit(queue, 1, &sf, s->fence[slot]);
    s->stats_mult = k;
  }

  VkResult res = g_extra && generated ? real_res : present_real();
  // The game sets no times here, so every timing record is one of ours.
  if (pace && d->GetPastPresentationTimingGOOGLE) {
    VkPastPresentationTimingGOOGLE past[16];
    uint32_t n = 16;
    if (d->GetPastPresentationTimingGOOGLE(d->handle, s->handle, &n, past) >= 0) {
      for (uint32_t i = 0; i < n; ++i) {
        const uint64_t t = past[i].actualPresentTime;
        if (!t) continue;
        ++s->stats_shown;
        s->vsync_ref = t;
        // Asked for half a refresh before its refresh: late if shown after it.
        if (past[i].desiredPresentTime && t > past[i].desiredPresentTime + s->refresh_ns) ++s->stats_late;
        // A refresh with nothing new while the game kept up: a visible hitch.
        if (s->last_shown && t > s->last_shown &&
            double(t - s->last_shown) > 1.5 * double(s->refresh_ns) && s->interval_ns < 1.25 * 2 * double(FrameNs()))
          ++s->stats_hitch;
        s->last_shown = t;
      }
    }
  }
  s->prev_valid = true;

  if (now - s->stats_start >= 1'000'000'000) {
    // The panel can change rate under us (Game Booster, adaptive refresh).
    if (d->display_timing && d->GetRefreshCycleDurationGOOGLE) {
      VkRefreshCycleDurationGOOGLE rc{};
      if (d->GetRefreshCycleDurationGOOGLE(d->handle, s->handle, &rc) == VK_SUCCESS && rc.refreshDuration &&
          rc.refreshDuration != s->refresh_ns) {
        // The reported duration jitters by a few microseconds; only a real change is news.
        if (std::abs(double(rc.refreshDuration) - double(s->refresh_ns)) > 0.02 * double(s->refresh_ns))
          LOGI("refresh now %.2f ms", rc.refreshDuration / 1e6);
        s->refresh_ns = rc.refreshDuration;
      }
    }
    // The game's own frame-rate hint (or the system's game mode) can pull the panel
    // back to the game's fps, which leaves no refresh to show a generated frame in.
    // Ask again for as long as the panel is slow.
    if (s->usable && s->window && double(s->refresh_ns) > 1.05 * double(FrameNs()) && __builtin_available(android 30, *))
      ANativeWindow_setFrameRate(s->window, float(g_target), ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
    double secs = double(now - s->stats_start) / 1e9;
    char gpu[128] = "";
    if (s->gpu_n) {
      snprintf(gpu, sizeof gpu, ", FI GPU %.1f ms (max %.1f), lead %d", s->gpu_sum / s->gpu_n, s->gpu_max, s->lead);
    }
    char shown[96] = "";
    if (s->stats_shown || s->stats_hitch)
      snprintf(shown, sizeof shown, ", shown %.0f fps, %d hitches, %d late", s->stats_shown / secs, s->stats_hitch,
               s->stats_late);
    // Lead from what the screen reports: frames shown later than asked mean their
    // work (game frame + FI) was not done in time. Up at once, down after 10 s clean;
    // never under 2 refreshes, where flipping cost visible hitches.
    if (s->stats_late > 2) {
      s->lead = std::min(3, s->lead + 1);
      s->clean_secs = 0;
    } else if (s->stats_late == 0 && ++s->clean_secs >= 10 && s->lead > 2) {
      --s->lead;
      s->clean_secs = 0;
    }
    LOGI("game %.0f fps, generated %.0f fps (%dx), target %d, screen %.0f Hz%s%s%s", s->stats_in / secs, s->stats_gen / secs,
         s->stats_mult, g_target, 1e9 / double(s->refresh_ns), gpu, shown, s->stats_skip ? " (no image for some)" : "");
    if (s->amd) {
      const std::string pr = s->amd->TakeProfile();
      if (!pr.empty()) LOGI("FI passes %s", pr.c_str());
    }
    s->gpu_sum = s->gpu_max = 0;
    s->gpu_n = 0;
    s->stats_start = now;
    s->stats_in = s->stats_gen = s->stats_skip = s->stats_shown = s->stats_hitch = s->stats_late = 0;
  }
  return res;
}

// ---------------------------------------------------------------- hooked entry points

Device* DeviceOf(VkDevice dev) {
  auto it = g_devices.find(dev);
  return it == g_devices.end() ? nullptr : it->second.get();
}

VKAPI_ATTR VkResult VKAPI_CALL HookCreateInstance(const VkInstanceCreateInfo* ci, const VkAllocationCallbacks* alloc,
                                                  VkInstance* out) {
  VkInstanceCreateInfo c = *ci;
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  if (ci->pApplicationInfo) app = *ci->pApplicationInfo;
  uint32_t loader = VK_API_VERSION_1_0;
  if (real_enum_version) real_enum_version(&loader);
  // ZeroFG records Vulkan 1.3 commands; an older instance would hide them.
  if (g_enabled && app.apiVersion < VK_API_VERSION_1_3 && loader >= VK_API_VERSION_1_3)
    app.apiVersion = VK_API_VERSION_1_3;
  c.pApplicationInfo = &app;
  VkResult r = real_create_instance(&c, alloc, out);
  if (r != VK_SUCCESS && app.apiVersion != (ci->pApplicationInfo ? ci->pApplicationInfo->apiVersion : 0u)) {
    r = real_create_instance(ci, alloc, out);
    app.apiVersion = ci->pApplicationInfo ? ci->pApplicationInfo->apiVersion : VK_API_VERSION_1_0;
  }
  if (r != VK_SUCCESS) return r;
  std::lock_guard<std::mutex> lk(g_m);
  Instance in;
  in.handle = *out;
  in.api = app.apiVersion ? app.apiVersion : VK_API_VERSION_1_0;
#define INST(field, name) in.field = reinterpret_cast<decltype(in.field)>(real_gipa(*out, name))
  INST(create_device, "vkCreateDevice");
  INST(props, "vkGetPhysicalDeviceProperties");
  INST(props2, "vkGetPhysicalDeviceProperties2");
  INST(features2, "vkGetPhysicalDeviceFeatures2");
  INST(queue_families, "vkGetPhysicalDeviceQueueFamilyProperties");
  INST(format_props, "vkGetPhysicalDeviceFormatProperties");
  INST(mem_props, "vkGetPhysicalDeviceMemoryProperties");
  INST(surface_caps, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
  INST(device_exts, "vkEnumerateDeviceExtensionProperties");
#undef INST
  g_inst = in;
  LOGI("instance: Vulkan %u.%u%s", VK_API_VERSION_MAJOR(in.api), VK_API_VERSION_MINOR(in.api),
       (ci->pApplicationInfo && ci->pApplicationInfo->apiVersion != in.api) ? " (raised for ZeroFG)" : "");
  return r;
}

template <typename T>
T* FindInChain(const void* next, VkStructureType type) {
  for (auto* p = reinterpret_cast<const VkBaseInStructure*>(next); p; p = p->pNext)
    if (p->sType == type) return reinterpret_cast<T*>(const_cast<VkBaseInStructure*>(p));
  return nullptr;
}

VKAPI_ATTR VkResult VKAPI_CALL HookCreateDevice(VkPhysicalDevice phys, const VkDeviceCreateInfo* ci,
                                                const VkAllocationCallbacks* alloc, VkDevice* out) {
  auto dev = std::make_unique<Device>();
  dev->phys = phys;
  VkPhysicalDeviceProperties props;
  g_inst.props(phys, &props);
  dev->api = props.apiVersion;

  VkPhysicalDeviceVulkan12Features q12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
  VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES, &q12};
  VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f13};
  bool want = g_enabled && g_inst.features2 && props.apiVersion >= VK_API_VERSION_1_3 &&
              g_inst.api >= VK_API_VERSION_1_3;
  if (want) {
    g_inst.features2(phys, &f2);
    want = f13.synchronization2 && f2.features.shaderStorageImageExtendedFormats &&
           (!g_amd || f2.features.shaderStorageImageWriteWithoutFormat);
    // What the LSFG shaders declare (SPIR-V OpCapability numbers).
    if (want && g_lsfg) {
      VkPhysicalDeviceVulkan12Features& v = q12;
      const struct { uint32_t cap; bool ok; const char* name; } need[] = {
          {9, bool(v.shaderFloat16), "shaderFloat16"},
          {22, bool(f2.features.shaderInt16), "shaderInt16"},
          {55, bool(f2.features.shaderStorageImageReadWithoutFormat), "storage read without format"},
          {56, bool(f2.features.shaderStorageImageWriteWithoutFormat), "storage write without format"},
          {5345, bool(v.vulkanMemoryModel), "vulkanMemoryModel"},
          {5346, bool(v.vulkanMemoryModelDeviceScope), "vulkanMemoryModelDeviceScope"},
      };
      for (const auto& n : need)
        if (LsfgCap(n.cap) && !n.ok) {
          LOGW("LSFG shaders need %s, which this GPU lacks", n.name);
          want = false;
        }
    }
  }

  VkDeviceCreateInfo c = *ci;
  VkPhysicalDeviceSynchronization2Features sync2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES};
  VkPhysicalDeviceFeatures features{};
  VkPhysicalDeviceShaderFloat16Int8Features f16{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES};
  VkPhysicalDeviceVulkanMemoryModelFeatures vmm{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES};
  bool fp16 = false;
  std::vector<const char*> exts(ci->ppEnabledExtensionNames, ci->ppEnabledExtensionNames + ci->enabledExtensionCount);
  if (want) {
    // Turn on what ZeroFG needs inside the game's own feature structs, or add them.
    auto* e13 = FindInChain<VkPhysicalDeviceVulkan13Features>(ci->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
    auto* es2 = FindInChain<VkPhysicalDeviceSynchronization2Features>(
        ci->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SYNCHRONIZATION_2_FEATURES);
    auto* ef2 = FindInChain<VkPhysicalDeviceFeatures2>(ci->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2);
    if (e13) e13->synchronization2 = VK_TRUE;
    if (es2) es2->synchronization2 = VK_TRUE;
    if (!e13 && !es2) {
      sync2.synchronization2 = VK_TRUE;
      sync2.pNext = const_cast<void*>(c.pNext);
      c.pNext = &sync2;
    }
    // FFX's 16-bit shaders (packed math on RDNA) need shaderFloat16 and shaderInt16.
    fp16 = g_amd ? q12.shaderFloat16 && f2.features.shaderInt16 : g_lsfg && LsfgCap(9);
    auto* e12 = FindInChain<VkPhysicalDeviceVulkan12Features>(ci->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
    if (g_lsfg && (LsfgCap(5345) || LsfgCap(5346))) {
      auto* emm = FindInChain<VkPhysicalDeviceVulkanMemoryModelFeatures>(
          ci->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_MEMORY_MODEL_FEATURES);
      VkBool32* model = e12 ? &e12->vulkanMemoryModel : emm ? &emm->vulkanMemoryModel : &vmm.vulkanMemoryModel;
      VkBool32* scope = e12 ? &e12->vulkanMemoryModelDeviceScope
                            : emm ? &emm->vulkanMemoryModelDeviceScope : &vmm.vulkanMemoryModelDeviceScope;
      *model = VK_TRUE;
      if (LsfgCap(5346)) *scope = VK_TRUE;
      if (!e12 && !emm) {
        vmm.pNext = const_cast<void*>(c.pNext);
        c.pNext = &vmm;
      }
    }
    if (fp16) {
      auto* ef16 = FindInChain<VkPhysicalDeviceShaderFloat16Int8Features>(
          ci->pNext, VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SHADER_FLOAT16_INT8_FEATURES);
      if (e12) e12->shaderFloat16 = VK_TRUE;
      if (ef16) ef16->shaderFloat16 = VK_TRUE;
      if (!e12 && !ef16) {
        f16.shaderFloat16 = VK_TRUE;
        f16.pNext = const_cast<void*>(c.pNext);
        c.pNext = &f16;
      }
    }
    // The AMD engine's output image is declared without a format.
    auto more = [&](VkPhysicalDeviceFeatures& f) {
      if (fp16 && g_amd) f.shaderInt16 = VK_TRUE;
      f.shaderStorageImageExtendedFormats = VK_TRUE;
      if (g_lsfg) {
        if (LsfgCap(22)) f.shaderInt16 = VK_TRUE;
        if (LsfgCap(55)) f.shaderStorageImageReadWithoutFormat = VK_TRUE;
        f.shaderStorageImageWriteWithoutFormat = f2.features.shaderStorageImageWriteWithoutFormat;
      }
      if (g_amd) {
        f.shaderStorageImageWriteWithoutFormat = VK_TRUE;
        f.shaderStorageImageReadWithoutFormat = f2.features.shaderStorageImageReadWithoutFormat;
      }
    };
    if (ef2) {
      more(ef2->features);
    } else {
      if (ci->pEnabledFeatures) features = *ci->pEnabledFeatures;
      more(features);
      c.pEnabledFeatures = &features;
    }
    uint32_t n = 0;
    g_inst.device_exts(phys, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> avail(n);
    g_inst.device_exts(phys, nullptr, &n, avail.data());
    auto has = [&](const char* name) {
      for (auto& e : avail)
        if (!strcmp(e.extensionName, name)) return true;
      return false;
    };
    auto enabled = [&](const char* name) {
      for (auto* e : exts)
        if (!strcmp(e, name)) return true;
      return false;
    };
    if (has(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)) {
      if (!enabled(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME)) exts.push_back(VK_GOOGLE_DISPLAY_TIMING_EXTENSION_NAME);
      dev->display_timing = true;
    }
    c.enabledExtensionCount = uint32_t(exts.size());
    c.ppEnabledExtensionNames = exts.data();
  }
  // One more queue, from a compute-only family, for the AMD engine.
  std::vector<VkDeviceQueueCreateInfo> qcis;
  std::vector<std::vector<float>> prios;
  uint32_t cq_family = UINT32_MAX, cq_index = 0;
  if (want && g_amd && g_async) {
    uint32_t nf = 0;
    g_inst.queue_families(phys, &nf, nullptr);
    std::vector<VkQueueFamilyProperties> fams(nf);
    g_inst.queue_families(phys, &nf, fams.data());
    for (uint32_t i = 0; i < nf; ++i)
      if ((fams[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && !(fams[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)) {
        cq_family = i;
        break;
      }
    if (cq_family != UINT32_MAX) {
      qcis.assign(ci->pQueueCreateInfos, ci->pQueueCreateInfos + ci->queueCreateInfoCount);
      prios.reserve(qcis.size() + 1);
      bool placed = false;
      for (auto& q : qcis) {
        if (q.queueFamilyIndex != cq_family) continue;
        if (q.queueCount >= fams[cq_family].queueCount) {
          cq_family = UINT32_MAX;  // the game took them all
        } else {
          prios.emplace_back(q.pQueuePriorities, q.pQueuePriorities + q.queueCount);
          prios.back().push_back(0.5f);
          cq_index = q.queueCount++;
          q.pQueuePriorities = prios.back().data();
        }
        placed = true;
      }
      if (!placed && cq_family != UINT32_MAX) {
        prios.push_back({0.5f});
        VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        q.queueFamilyIndex = cq_family;
        q.queueCount = 1;
        q.pQueuePriorities = prios.back().data();
        qcis.push_back(q);
      }
      if (cq_family != UINT32_MAX) {
        c.queueCreateInfoCount = uint32_t(qcis.size());
        c.pQueueCreateInfos = qcis.data();
      }
    }
  }
  VkResult r = g_inst.create_device(phys, &c, alloc, out);
  if (r != VK_SUCCESS && cq_family != UINT32_MAX) {
    LOGW("vkCreateDevice with a compute queue failed (%d); retrying without", r);
    cq_family = UINT32_MAX;
    c.queueCreateInfoCount = ci->queueCreateInfoCount;
    c.pQueueCreateInfos = ci->pQueueCreateInfos;
    r = g_inst.create_device(phys, &c, alloc, out);
  }
  if (r != VK_SUCCESS && want) {
    LOGW("vkCreateDevice with ZeroFG's features failed (%d); retrying as the game asked", r);
    want = false;
    dev->display_timing = false;
    fp16 = false;
    r = g_inst.create_device(phys, ci, alloc, out);
  }
  if (r == VK_SUCCESS && g_amd) zerofg_ffx_fp16 = fp16;
  if (r != VK_SUCCESS) return r;

  dev->handle = *out;
  dev->capable = want;
#define DEV_LOAD(name) dev->name = reinterpret_cast<PFN_vk##name>(real_gdpa(*out, "vk" #name));
  DEV_FNS(DEV_LOAD)
#undef DEV_LOAD
  if (want && cq_family != UINT32_MAX) {
    dev->GetDeviceQueue(*out, cq_family, cq_index, &dev->cq);
    if (dev->cq) {
      dev->cq_family = cq_family;
      dev->cq_index = cq_index;
    }
  }
  uint32_t n = 0;
  g_inst.queue_families(phys, &n, nullptr);
  dev->families.resize(n);
  g_inst.queue_families(phys, &n, dev->families.data());
  if (g_inst.props2) {
    dev->subgroup.pNext = &dev->subgroup_size;
    VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &dev->subgroup};
    g_inst.props2(phys, &p2);
    dev->subgroup.pNext = nullptr;
  }
  char cqs[48] = "";
  if (dev->cq) snprintf(cqs, sizeof cqs, ", compute queue %u.%u", dev->cq_family, dev->cq_index);
  LOGI("device %s, Vulkan %u.%u: frame generation %s%s%s", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
       VK_API_VERSION_MINOR(props.apiVersion), want ? "possible" : "off", fp16 ? " (16-bit shaders)" : "", cqs);
  std::lock_guard<std::mutex> lk(g_m);
  g_devices[*out] = std::move(dev);
  return r;
}

VKAPI_ATTR void VKAPI_CALL HookDestroyDevice(VkDevice device, const VkAllocationCallbacks* alloc) {
  std::unique_ptr<Device> dev;
  {
    std::lock_guard<std::mutex> lk(g_m);
    auto it = g_devices.find(device);
    if (it != g_devices.end()) {
      dev = std::move(it->second);
      g_devices.erase(it);
    }
    for (auto s = g_swaps.begin(); s != g_swaps.end();) {
      if (s->second->dev == dev.get()) {
        DestroySwapState(s->second.get());
        s = g_swaps.erase(s);
      } else {
        ++s;
      }
    }
    for (auto q = g_queues.begin(); q != g_queues.end();)
      q = q->second.dev == dev.get() ? g_queues.erase(q) : std::next(q);
  }
  auto destroy = dev ? dev->DestroyDevice
                     : reinterpret_cast<PFN_vkDestroyDevice>(real_gdpa(device, "vkDestroyDevice"));
  destroy(device, alloc);
}

VKAPI_ATTR void VKAPI_CALL HookGetDeviceQueue(VkDevice device, uint32_t family, uint32_t index, VkQueue* out) {
  std::lock_guard<std::mutex> lk(g_m);
  Device* d = DeviceOf(device);
  auto fn = d ? d->GetDeviceQueue : reinterpret_cast<PFN_vkGetDeviceQueue>(real_gdpa(device, "vkGetDeviceQueue"));
  fn(device, family, index, out);
  if (d && *out) g_queues[*out] = {d, family};
}

VKAPI_ATTR void VKAPI_CALL HookGetDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2* info, VkQueue* out) {
  std::lock_guard<std::mutex> lk(g_m);
  Device* d = DeviceOf(device);
  auto fn = d ? d->GetDeviceQueue2 : reinterpret_cast<PFN_vkGetDeviceQueue2>(real_gdpa(device, "vkGetDeviceQueue2"));
  fn(device, info, out);
  if (d && *out) g_queues[*out] = {d, info->queueFamilyIndex};
}

VKAPI_ATTR VkResult VKAPI_CALL HookCreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR* ci,
                                                      const VkAllocationCallbacks* alloc, VkSwapchainKHR* out) {
  Device* d;
  {
    std::lock_guard<std::mutex> lk(g_m);
    d = DeviceOf(device);
  }
  if (!d) {
    auto fn = reinterpret_cast<PFN_vkCreateSwapchainKHR>(real_gdpa(device, "vkCreateSwapchainKHR"));
    return fn(device, ci, alloc, out);
  }
  VkSwapchainCreateInfoKHR c = *ci;
  bool usable = false;
  if (d->capable && g_inst.surface_caps && ci->imageArrayLayers == 1) {
    VkSurfaceCapabilitiesKHR caps{};
    g_inst.surface_caps(d->phys, ci->surface, &caps);
    const VkImageUsageFlags need = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if ((caps.supportedUsageFlags & need) == need) {
      c.imageUsage |= need;
      // Room for the generated frames' images next to the game's own.
      c.minImageCount = ci->minImageCount + (kMaxMult - 1);
      if (caps.maxImageCount) c.minImageCount = std::min(c.minImageCount, caps.maxImageCount);
      // Two presents per game frame, each on its own refresh.
      c.presentMode = VK_PRESENT_MODE_FIFO_KHR;
      usable = true;
    }
  }
  VkResult r = d->CreateSwapchainKHR(device, &c, alloc, out);
  if (r != VK_SUCCESS && usable) {
    usable = false;
    r = d->CreateSwapchainKHR(device, ci, alloc, out);
  }
  if (r != VK_SUCCESS) return r;
  auto s = std::make_unique<Swap>();
  s->dev = d;
  s->handle = *out;
  {
    std::lock_guard<std::mutex> lk(g_m);
    auto w = g_windows.find(ci->surface);
    if (w != g_windows.end()) s->window = w->second;
  }
  // A game at 60 on a 60 Hz panel leaves no refresh for a generated frame: ask for
  // the panel's fastest rate (the game's own hint is for its own fps).
  if (usable && s->window && __builtin_available(android 30, *)) {
    int fr = ANativeWindow_setFrameRate(s->window, float(g_target), ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
    LOGI("target %d fps, asked the screen for it: %d", g_target, fr);
  }
  s->format = ci->imageFormat;
  s->extent = ci->imageExtent;
  s->usable = usable;
  uint32_t n = 0;
  d->GetSwapchainImagesKHR(device, *out, &n, nullptr);
  s->images.resize(n);
  d->GetSwapchainImagesKHR(device, *out, &n, s->images.data());
  LOGI("swapchain %ux%u format %d, %u images (game asked %u), present mode %d -> %s", ci->imageExtent.width,
       ci->imageExtent.height, ci->imageFormat, n, ci->minImageCount, ci->presentMode,
       usable ? "frame generation" : "untouched");
  std::lock_guard<std::mutex> lk(g_m);
  g_swaps[*out] = std::move(s);
  return r;
}

VKAPI_ATTR void VKAPI_CALL HookDestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain,
                                                   const VkAllocationCallbacks* alloc) {
  PFN_vkDestroySwapchainKHR fn = nullptr;
  {
    std::lock_guard<std::mutex> lk(g_m);
    auto it = g_swaps.find(swapchain);
    if (it != g_swaps.end()) {
      DestroySwapState(it->second.get());
      g_swaps.erase(it);
    }
    if (Device* d = DeviceOf(device)) fn = d->DestroySwapchainKHR;
  }
  if (!fn) fn = reinterpret_cast<PFN_vkDestroySwapchainKHR>(real_gdpa(device, "vkDestroySwapchainKHR"));
  fn(device, swapchain, alloc);
}

VKAPI_ATTR VkResult VKAPI_CALL HookQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR* pi) {
  std::lock_guard<std::mutex> lk(g_m);
  auto q = g_queues.find(queue);
  Swap* s = nullptr;
  if (q != g_queues.end() && pi->swapchainCount == 1) {
    auto it = g_swaps.find(pi->pSwapchains[0]);
    if (it != g_swaps.end()) s = it->second.get();
  }
  if (q == g_queues.end()) {
    // A queue we never saw (fetched before the wrapper existed): forward via any device.
    static PFN_vkQueuePresentKHR fallback = nullptr;
    if (!fallback && !g_devices.empty()) fallback = g_devices.begin()->second->QueuePresentKHR;
    return fallback ? fallback(queue, pi) : VK_ERROR_DEVICE_LOST;
  }
  Device* d = q->second.dev;
  if (s && s->usable && !s->failed && s->dev == d) {
    const uint32_t family = q->second.family;
    if (!s->ready) {
      if ((d->families[family].queueFlags & VK_QUEUE_COMPUTE_BIT) && InitSwap(s, family)) {
        s->ready = true;
      } else {
        LOGW("frame generation unavailable on this swapchain");
        DestroySwapState(s);
        s->failed = true;
      }
    }
    if (s->ready && s->family == family) {
      const VkResult r = FgPresent(s, queue, pi);
      // The other slot holds the previous frame (kSlots == 2); the next present
      // would wait for it anyway, this only moves the wait before the game's next frame.
      if (g_lowlat) d->WaitForFences(d->handle, 1, &s->fence[s->slot], VK_TRUE, 100'000'000);
      return r;
    }
  }
  return d->QueuePresentKHR(queue, pi);
}

VKAPI_ATTR VkResult VKAPI_CALL HookCreateAndroidSurfaceKHR(VkInstance instance, const VkAndroidSurfaceCreateInfoKHR* ci,
                                                           const VkAllocationCallbacks* alloc, VkSurfaceKHR* out) {
  static PFN_vkCreateAndroidSurfaceKHR fn = nullptr;
  if (!fn) fn = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(real_gipa(instance, "vkCreateAndroidSurfaceKHR"));
  VkResult r = fn(instance, ci, alloc, out);
  if (r == VK_SUCCESS) {
    std::lock_guard<std::mutex> lk(g_m);
    g_windows[*out] = ci->window;
  }
  return r;
}

PFN_vkVoidFunction Hooked(const char* name) {
#define HOOK(n, fn) \
  if (!strcmp(name, n)) return reinterpret_cast<PFN_vkVoidFunction>(fn);
  HOOK("vkCreateInstance", HookCreateInstance)
  HOOK("vkCreateDevice", HookCreateDevice)
  HOOK("vkDestroyDevice", HookDestroyDevice)
  HOOK("vkGetDeviceQueue", HookGetDeviceQueue)
  HOOK("vkGetDeviceQueue2", HookGetDeviceQueue2)
  HOOK("vkCreateSwapchainKHR", HookCreateSwapchainKHR)
  HOOK("vkDestroySwapchainKHR", HookDestroySwapchainKHR)
  HOOK("vkQueuePresentKHR", HookQueuePresentKHR)
  HOOK("vkCreateAndroidSurfaceKHR", HookCreateAndroidSurfaceKHR)
#undef HOOK
  return nullptr;
}

}  // namespace

// ---------------------------------------------------------------- exports

#define BBW_TRAMPOLINE(name) extern "C" void* bbw_p_##name;
#include "trampolines.inc"
#undef BBW_TRAMPOLINE

BBW_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name);

BBW_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance, const char* name) {
  if (!strcmp(name, "vkGetInstanceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetInstanceProcAddr);
  if (!strcmp(name, "vkGetDeviceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetDeviceProcAddr);
  PFN_vkVoidFunction real = real_gipa(instance, name);
  PFN_vkVoidFunction hook = Hooked(name);
  return hook && real ? hook : real;
}

BBW_EXPORT PFN_vkVoidFunction VKAPI_CALL vkGetDeviceProcAddr(VkDevice device, const char* name) {
  if (!strcmp(name, "vkGetDeviceProcAddr")) return reinterpret_cast<PFN_vkVoidFunction>(vkGetDeviceProcAddr);
  PFN_vkVoidFunction real = real_gdpa(device, name);
  PFN_vkVoidFunction hook = Hooked(name);
  return hook && real ? hook : real;
}

BBW_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo* ci,
                                                           const VkAllocationCallbacks* alloc, VkInstance* out) {
  return HookCreateInstance(ci, alloc, out);
}
BBW_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateDevice(VkPhysicalDevice phys, const VkDeviceCreateInfo* ci,
                                                         const VkAllocationCallbacks* alloc, VkDevice* out) {
  return HookCreateDevice(phys, ci, alloc, out);
}
BBW_EXPORT VKAPI_ATTR void VKAPI_CALL vkDestroyDevice(VkDevice device, const VkAllocationCallbacks* alloc) {
  HookDestroyDevice(device, alloc);
}
BBW_EXPORT VKAPI_ATTR void VKAPI_CALL vkGetDeviceQueue(VkDevice device, uint32_t family, uint32_t index,
                                                       VkQueue* out) {
  HookGetDeviceQueue(device, family, index, out);
}
BBW_EXPORT VKAPI_ATTR void VKAPI_CALL vkGetDeviceQueue2(VkDevice device, const VkDeviceQueueInfo2* info,
                                                        VkQueue* out) {
  HookGetDeviceQueue2(device, info, out);
}
BBW_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateSwapchainKHR(VkDevice device, const VkSwapchainCreateInfoKHR* ci,
                                                               const VkAllocationCallbacks* alloc,
                                                               VkSwapchainKHR* out) {
  return HookCreateSwapchainKHR(device, ci, alloc, out);
}
BBW_EXPORT VKAPI_ATTR void VKAPI_CALL vkDestroySwapchainKHR(VkDevice device, VkSwapchainKHR swapchain,
                                                            const VkAllocationCallbacks* alloc) {
  HookDestroySwapchainKHR(device, swapchain, alloc);
}
BBW_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkCreateAndroidSurfaceKHR(VkInstance instance,
                                                                    const VkAndroidSurfaceCreateInfoKHR* ci,
                                                                    const VkAllocationCallbacks* alloc,
                                                                    VkSurfaceKHR* out) {
  return HookCreateAndroidSurfaceKHR(instance, ci, alloc, out);
}
BBW_EXPORT VKAPI_ATTR VkResult VKAPI_CALL vkQueuePresentKHR(VkQueue queue, const VkPresentInfoKHR* pi) {
  return HookQueuePresentKHR(queue, pi);
}

// Called by NewBlackbox's GpuBridge before the game runs. config: "key=value;..."
// (log=<file>, fg=0|1).
BBW_EXPORT bool bb_vulkan_wrapper_init(void* real, const char* config) {
  std::string cfg = config ? config : "";
  std::string pack;
  size_t pos = 0;
  while (pos < cfg.size()) {
    size_t end = cfg.find(';', pos);
    if (end == std::string::npos) end = cfg.size();
    std::string kv = cfg.substr(pos, end - pos);
    size_t eq = kv.find('=');
    if (eq != std::string::npos) {
      std::string k = kv.substr(0, eq), v = kv.substr(eq + 1);
      if (k == "log" && !v.empty()) g_log = fopen(v.c_str(), "w");
      if (k == "fg") g_enabled = v != "0";
      if (k == "pace") g_pace = v != "0";
      if (k == "async") g_async = false;  // retired (see the AMD engine setup)
      if (k == "extra") g_extra = v == "1";
      if (k == "target") g_target = std::clamp(atoi(v.c_str()), 30, 165);
      if (k == "scale") g_scale = std::clamp(atoi(v.c_str()), 25, 100);
      if (k == "engine") g_lsfg = v == "lsfg";
      if (k == "lowlat") g_lowlat = v == "1";
      if (k == "edge") g_edge = v != "0";
      if (k == "mult") g_max_mult = std::clamp(atoi(v.c_str()), 2, kMaxMult);
      if (k == "lsfg") pack = v;
    }
    pos = end + 1;
  }
  // LSFG only: the app no longer offers FSR 3.1 (its code stays for now; engine=fsr
  // in a config still selects it). Without the shaders, no frame generation.
  g_zero = false;
  if (g_lsfg) {
    std::string err;
    if (pack.empty() || !lsfg::LoadPack(pack.c_str(), &g_pack, &err)) {
      LOGW("LSFG shaders not loaded (%s); frame generation off. Import Lossless.dll in the app.",
           pack.empty() ? "no pack given" : err.c_str());
      g_lsfg = false;
      g_enabled = false;
    } else {
      g_lsfg_caps = lsfg::Capabilities(g_pack);
      std::string caps;
      for (uint32_t c : g_lsfg_caps) caps += " " + std::to_string(c);
      LOGI("LSFG pack: %zu shaders, SPIR-V capabilities%s", g_pack.size(), caps.c_str());
    }
  }
  g_amd = !g_lsfg;
  if (g_amd) g_max_mult = 2;
  real_gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(real, "vkGetInstanceProcAddr"));
  real_gdpa = reinterpret_cast<PFN_vkGetDeviceProcAddr>(dlsym(real, "vkGetDeviceProcAddr"));
  real_create_instance = reinterpret_cast<PFN_vkCreateInstance>(dlsym(real, "vkCreateInstance"));
  real_enum_version = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(dlsym(real, "vkEnumerateInstanceVersion"));
  if (!real_gipa || !real_gdpa || !real_create_instance) {
    LOGW("real libvulkan lacks its entry points");
    return false;
  }
  int missing = 0;
#define BBW_TRAMPOLINE(name)                    \
  bbw_p_##name = dlsym(real, #name);            \
  if (!bbw_p_##name) ++missing;
#include "trampolines.inc"
#undef BBW_TRAMPOLINE
  LOGI("wrapper ready (frame generation %s%s%s, %d exports missing in the real loader)", g_enabled ? "on" : "off",
       g_lsfg ? ", LSFG" : ", AMD FidelityFX", g_lowlat ? ", low latency" : "", missing);
  return true;
}

// Zygisk build: the wrapper is part of the module library, so there is no dlopen
// handle to give the game. The module answers the game's dlsym through this.
#define BBW_TRAMPOLINE(name) extern "C" void name();
#include "trampolines.inc"
#undef BBW_TRAMPOLINE

BBW_EXPORT void* zfg_wrapper_symbol(const char* name) {
  if (!name) return nullptr;
#define OWN(n) \
  if (!strcmp(name, #n)) return reinterpret_cast<void*>(&n);
  OWN(vkGetInstanceProcAddr) OWN(vkGetDeviceProcAddr) OWN(vkCreateInstance) OWN(vkCreateDevice)
  OWN(vkDestroyDevice) OWN(vkGetDeviceQueue) OWN(vkGetDeviceQueue2) OWN(vkCreateSwapchainKHR)
  OWN(vkDestroySwapchainKHR) OWN(vkCreateAndroidSurfaceKHR) OWN(vkQueuePresentKHR)
#undef OWN
#define BBW_TRAMPOLINE(n) \
  if (!strcmp(name, #n)) return bbw_p_##n ? reinterpret_cast<void*>(&n) : nullptr;
#include "trampolines.inc"
#undef BBW_TRAMPOLINE
  return nullptr;
}
