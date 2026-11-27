// LSFG-style frame generation engine (see lsfg.h).
#include "lsfg.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>

namespace lsfg {

// ------------------------------------------------------------------ shader pack

bool LoadPack(const char* path, ShaderPack* out, std::string* err) {
  FILE* f = fopen(path, "rb");
  if (!f) {
    *err = std::string("cannot open ") + path;
    return false;
  }
  auto rd = [&](uint32_t* v) { return fread(v, 4, 1, f) == 1; };
  uint32_t magic = 0, count = 0;
  bool ok = rd(&magic) && magic == 0x5046534c /* "LSFP" */ && rd(&count) && count < 1024;
  for (uint32_t i = 0; ok && i < count; ++i) {
    uint32_t id = 0, n = 0;
    ok = rd(&id) && rd(&n) && n >= 5 && n < (16u << 20);
    if (!ok) break;
    std::vector<uint32_t> words(n);
    ok = fread(words.data(), 4, n, f) == n && words[0] == 0x07230203;
    if (ok) (*out)[id] = std::move(words);
  }
  fclose(f);
  if (!ok) *err = "the shader pack is damaged";
  return ok;
}

std::set<uint32_t> Capabilities(const ShaderPack& pack) {
  std::set<uint32_t> caps;
  for (const auto& [id, w] : pack) {
    for (size_t i = 5; i < w.size();) {
      const uint32_t len = w[i] >> 16, op = w[i] & 0xffff;
      if (!len) break;
      if (op == 17 /* OpCapability */ && len >= 2) caps.insert(w[i + 1]);
      if (op == 54 /* OpFunction */) break;
      i += len;
    }
  }
  return caps;
}

namespace {

// Lossless.dll's shaders number their bindings per HLSL register kind; the passes
// below bind everything in one set, in order: uniform buffer, samplers, sampled
// images, storage images. That is the order (set, binding) sorts them into, so the
// n-th resource by (set, binding) becomes binding n of set 0.
std::vector<uint32_t> Rebind(std::vector<uint32_t> w, std::string* entry) {
  struct Ref {
    uint32_t target, set, binding;
    size_t at;
  };
  std::map<uint32_t, uint32_t> set_of;
  std::map<uint32_t, size_t> set_at;
  std::vector<Ref> refs;
  for (size_t i = 5; i < w.size();) {
    const uint32_t len = w[i] >> 16, op = w[i] & 0xffff;
    if (!len || i + len > w.size()) break;
    if (op == 54) break;  // OpFunction: no more decorations
    if (op == 15 && len >= 4 && entry->empty())  // OpEntryPoint model id "name"
      *entry = reinterpret_cast<const char*>(&w[i + 3]);
    if (op == 71 && len >= 4) {  // OpDecorate target decoration literal
      if (w[i + 2] == 34) {      // DescriptorSet
        set_of[w[i + 1]] = w[i + 3];
        set_at[w[i + 1]] = i + 3;
      } else if (w[i + 2] == 33) {  // Binding
        refs.push_back({w[i + 1], 0, w[i + 3], i + 3});
      }
    }
    i += len;
  }
  for (auto& r : refs) r.set = set_of.count(r.target) ? set_of[r.target] : 0;
  std::stable_sort(refs.begin(), refs.end(),
                   [](const Ref& a, const Ref& b) { return a.set != b.set ? a.set < b.set : a.binding < b.binding; });
  for (size_t n = 0; n < refs.size(); ++n) w[refs[n].at] = uint32_t(n);
  for (auto& [t, at] : set_at) w[at] = 0;
  if (entry->empty()) *entry = "main";
  return w;
}

// Shader ids in Lossless.dll (the 16-bit "native" builds sit 49 ids higher; the
// app's pack already holds those under these ids).
constexpr uint32_t kMipmaps = 255, kGenerate = 256;
constexpr uint32_t kAlpha[4] = {290, 291, 292, 293};
constexpr uint32_t kBeta[5] = {298, 299, 300, 301, 302};
constexpr uint32_t kGamma[5] = {280, 282, 283, 284, 285};
constexpr uint32_t kDelta[10] = {280, 286, 287, 288, 289, 281, 294, 295, 296, 297};

constexpr int kLevels = 7;   // flow pyramid
constexpr int kBetaOut = 6;
constexpr int kSlots = kMaxGenerations * (kMaxGenerations + 1) / 2;  // (count, index) pairs

constexpr VkFormat kColor = VK_FORMAT_R8G8B8A8_UNORM;
constexpr VkFormat kFlow = VK_FORMAT_R8_UNORM;
constexpr VkFormat kMotion = VK_FORMAT_R16G16B16A16_SFLOAT;

// The shaders' constant buffer.
struct Constants {
  uint32_t input_offset[2];
  uint32_t first_iter, first_iter_s;
  uint32_t advanced_color_kind, hdr_support;
  float resolution_inv_scale, timestamp, ui_threshold;
  uint32_t pad[3];
};
static_assert(sizeof(Constants) == 48);

int SlotOf(int count, int index) { return (count - 1) * count / 2 + index; }

uint32_t Groups(uint32_t size, int shift) { return (size + (1u << shift) - 1) >> shift; }

VkExtent2D Half(VkExtent2D e) { return {(e.width + 1) >> 1, (e.height + 1) >> 1}; }
VkExtent2D Shift(VkExtent2D e, int s) { return {std::max(1u, e.width >> s), std::max(1u, e.height >> s)}; }

}  // namespace

// ------------------------------------------------------------------ engine

struct Engine::Impl {
#define LSFG_FNS(X)                                                                                \
  X(CreateShaderModule) X(DestroyShaderModule) X(CreateDescriptorSetLayout)                     \
  X(DestroyDescriptorSetLayout) X(CreatePipelineLayout) X(DestroyPipelineLayout)                \
  X(CreateComputePipelines) X(DestroyPipeline) X(CreateDescriptorPool) X(DestroyDescriptorPool) \
  X(AllocateDescriptorSets) X(UpdateDescriptorSets) X(CreateSampler) X(DestroySampler)          \
  X(CreateImage) X(DestroyImage) X(GetImageMemoryRequirements) X(AllocateMemory) X(FreeMemory)  \
  X(BindImageMemory) X(CreateImageView) X(DestroyImageView) X(CreateBuffer) X(DestroyBuffer)    \
  X(GetBufferMemoryRequirements) X(BindBufferMemory) X(MapMemory) X(CmdBindPipeline)            \
  X(CmdBindDescriptorSets) X(CmdDispatch) X(CmdPipelineBarrier) X(CmdClearColorImage)
#define LSFG_FN(n) PFN_vk##n n = nullptr;
  LSFG_FNS(LSFG_FN)
#undef LSFG_FN

  VkDevice dev = VK_NULL_HANDLE;
  VkPhysicalDeviceMemoryProperties mem{};
  const ShaderPack* pack = nullptr;
  std::string err;

  struct Image {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkExtent2D extent{};
  };
  std::vector<std::unique_ptr<Image>> images;  // everything we own, for barriers and cleanup
  bool initialized = false;

  // What one descriptor binding gets.
  enum Kind { kUniform, kSampler, kSampled, kStorage };
  struct Res {
    Kind kind;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkImageLayout layout = VK_IMAGE_LAYOUT_GENERAL;
  };

  struct Pipeline {
    VkShaderModule module = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
    VkPipelineLayout layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
  };
  std::map<std::pair<uint32_t, std::string>, Pipeline> pipelines;

  // Descriptor sets vary by nothing, by the history slot (frame % 3), or by which
  // of the two frames is the newest.
  enum Vary { kFixed, kHistory, kNewest };
  struct Step {
    Pipeline* pipe;
    Vary vary;
    VkDescriptorSet sets[3]{};
    uint32_t gx, gy;
    bool barrier;  // wait for the steps before (false: independent of the last step)
  };
  std::vector<Step> shared;            // per game frame
  std::vector<Step> generate[kSlots];  // per generated frame

  std::vector<VkDescriptorPool> pools;
  std::map<uint64_t, VkBuffer> buffers;
  std::vector<VkDeviceMemory> buffer_memory;
  VkSampler sampler = VK_NULL_HANDLE, white = VK_NULL_HANDLE, edge = VK_NULL_HANDLE;
  float flow_scale = 1;

  ~Impl() {
    if (!dev) return;
    for (auto& [k, p] : pipelines) {
      if (p.pipeline) DestroyPipeline(dev, p.pipeline, nullptr);
      if (p.layout) DestroyPipelineLayout(dev, p.layout, nullptr);
      if (p.set_layout) DestroyDescriptorSetLayout(dev, p.set_layout, nullptr);
      if (p.module) DestroyShaderModule(dev, p.module, nullptr);
    }
    for (auto pool : pools) DestroyDescriptorPool(dev, pool, nullptr);
    for (auto& [k, b] : buffers) DestroyBuffer(dev, b, nullptr);
    for (auto m : buffer_memory) FreeMemory(dev, m, nullptr);
    for (VkSampler s : {sampler, white, edge})
      if (s) DestroySampler(dev, s, nullptr);
    for (auto& im : images) {
      if (im->view) DestroyImageView(dev, im->view, nullptr);
      if (im->image) DestroyImage(dev, im->image, nullptr);
      if (im->memory) FreeMemory(dev, im->memory, nullptr);
    }
  }

  uint32_t MemoryType(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < mem.memoryTypeCount; ++i)
      if ((bits & (1u << i)) && (mem.memoryTypes[i].propertyFlags & want) == want) return i;
    return UINT32_MAX;
  }

  Image* NewImage(VkExtent2D e, VkFormat f) {
    auto im = std::make_unique<Image>();
    im->extent = {std::max(1u, e.width), std::max(1u, e.height)};
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = f;
    ci.extent = {im->extent.width, im->extent.height, 1};
    ci.mipLevels = ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    if (CreateImage(dev, &ci, nullptr, &im->image) != VK_SUCCESS) return Fail("image");
    VkMemoryRequirements req;
    GetImageMemoryRequirements(dev, im->image, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = MemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (ai.memoryTypeIndex == UINT32_MAX) ai.memoryTypeIndex = MemoryType(req.memoryTypeBits, 0);
    if (AllocateMemory(dev, &ai, nullptr, &im->memory) != VK_SUCCESS ||
        BindImageMemory(dev, im->image, im->memory, 0) != VK_SUCCESS)
      return Fail("image memory");
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = im->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = f;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    if (CreateImageView(dev, &vi, nullptr, &im->view) != VK_SUCCESS) return Fail("image view");
    images.push_back(std::move(im));
    return images.back().get();
  }

  Image* Fail(const char* what) {
    if (err.empty()) err = std::string(what) + " creation failed";
    return nullptr;
  }

  VkSampler NewSampler(VkSamplerAddressMode mode, VkBorderColor border) {
    VkSamplerCreateInfo ci{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    ci.magFilter = ci.minFilter = VK_FILTER_LINEAR;
    ci.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    ci.addressModeU = ci.addressModeV = ci.addressModeW = mode;
    ci.maxLod = VK_LOD_CLAMP_NONE;
    ci.borderColor = border;
    VkSampler s = VK_NULL_HANDLE;
    if (CreateSampler(dev, &ci, nullptr, &s) != VK_SUCCESS) err = "sampler creation failed";
    return s;
  }

  VkBuffer Uniform(float timestamp, bool first_iter = false, bool first_iter_s = false) {
    uint32_t bits;
    memcpy(&bits, &timestamp, 4);
    const uint64_t key = bits | uint64_t(first_iter) << 32 | uint64_t(first_iter_s) << 33;
    if (auto it = buffers.find(key); it != buffers.end()) return it->second;
    VkBufferCreateInfo ci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    ci.size = sizeof(Constants);
    ci.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    VkBuffer b = VK_NULL_HANDLE;
    if (CreateBuffer(dev, &ci, nullptr, &b) != VK_SUCCESS) {
      err = "buffer creation failed";
      return VK_NULL_HANDLE;
    }
    buffers[key] = b;
    VkMemoryRequirements req;
    GetBufferMemoryRequirements(dev, b, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex =
        MemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkDeviceMemory m = VK_NULL_HANDLE;
    void* p = nullptr;
    if (ai.memoryTypeIndex == UINT32_MAX || AllocateMemory(dev, &ai, nullptr, &m) != VK_SUCCESS ||
        BindBufferMemory(dev, b, m, 0) != VK_SUCCESS || MapMemory(dev, m, 0, VK_WHOLE_SIZE, 0, &p) != VK_SUCCESS) {
      err = "buffer memory failed";
      return b;
    }
    buffer_memory.push_back(m);
    Constants c{};
    c.first_iter = first_iter;
    c.first_iter_s = first_iter_s;
    c.resolution_inv_scale = 1.f / flow_scale;
    c.timestamp = timestamp;
    c.ui_threshold = 0.5f;
    memcpy(p, &c, sizeof c);  // stays mapped; freed with the memory
    return b;
  }

  Pipeline* PipelineFor(uint32_t id, const std::vector<Res>& res) {
    std::string kinds;
    for (auto& r : res) kinds += char('0' + r.kind);
    auto key = std::make_pair(id, kinds);
    if (auto it = pipelines.find(key); it != pipelines.end()) return &it->second;
    Pipeline& p = pipelines[key];
    auto src = pack->find(id);
    if (src == pack->end()) {
      err = "shader " + std::to_string(id) + " missing from the pack";
      return nullptr;
    }
    std::string entry;
    std::vector<uint32_t> code = Rebind(src->second, &entry);
    VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    sm.codeSize = code.size() * 4;
    sm.pCode = code.data();
    std::vector<VkDescriptorSetLayoutBinding> b(res.size());
    static constexpr VkDescriptorType kType[] = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_DESCRIPTOR_TYPE_SAMPLER,
                                                 VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
    for (size_t i = 0; i < res.size(); ++i)
      b[i] = {uint32_t(i), kType[res[i].kind], 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dl.bindingCount = uint32_t(b.size());
    dl.pBindings = b.data();
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &p.set_layout;
    if (CreateShaderModule(dev, &sm, nullptr, &p.module) != VK_SUCCESS ||
        CreateDescriptorSetLayout(dev, &dl, nullptr, &p.set_layout) != VK_SUCCESS ||
        CreatePipelineLayout(dev, &pl, nullptr, &p.layout) != VK_SUCCESS) {
      err = "pipeline layout for shader " + std::to_string(id) + " failed";
      return nullptr;
    }
    VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cp.stage.module = p.module;
    cp.stage.pName = entry.c_str();
    cp.layout = p.layout;
    const VkResult r = CreateComputePipelines(dev, VK_NULL_HANDLE, 1, &cp, nullptr, &p.pipeline);
    if (r != VK_SUCCESS) {
      err = "compute pipeline for shader " + std::to_string(id) + " failed: " + std::to_string(r);
      return nullptr;
    }
    return &p;
  }

  VkDescriptorSet NewSet(VkDescriptorSetLayout layout) {
    VkDescriptorSet set = VK_NULL_HANDLE;
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &layout;
    if (!pools.empty()) {
      ai.descriptorPool = pools.back();
      if (AllocateDescriptorSets(dev, &ai, &set) == VK_SUCCESS) return set;
    }
    const VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 256},
                                          {VK_DESCRIPTOR_TYPE_SAMPLER, 512},
                                          {VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE, 1024},
                                          {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 768}};
    VkDescriptorPoolCreateInfo pc{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pc.maxSets = 256;
    pc.poolSizeCount = 4;
    pc.pPoolSizes = sizes;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    if (CreateDescriptorPool(dev, &pc, nullptr, &pool) != VK_SUCCESS) {
      err = "descriptor pool creation failed";
      return VK_NULL_HANDLE;
    }
    pools.push_back(pool);
    ai.descriptorPool = pool;
    if (AllocateDescriptorSets(dev, &ai, &set) != VK_SUCCESS) err = "descriptor set allocation failed";
    return set;
  }

  void Write(VkDescriptorSet set, const std::vector<Res>& res) {
    std::vector<VkWriteDescriptorSet> w(res.size());
    std::vector<VkDescriptorImageInfo> ii(res.size());
    std::vector<VkDescriptorBufferInfo> bi(res.size());
    for (size_t i = 0; i < res.size(); ++i) {
      w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
      w[i].dstSet = set;
      w[i].dstBinding = uint32_t(i);
      w[i].descriptorCount = 1;
      switch (res[i].kind) {
        case kUniform:
          w[i].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
          bi[i] = {res[i].buffer, 0, sizeof(Constants)};
          w[i].pBufferInfo = &bi[i];
          break;
        case kSampler:
          w[i].descriptorType = VK_DESCRIPTOR_TYPE_SAMPLER;
          ii[i].sampler = res[i].sampler;
          w[i].pImageInfo = &ii[i];
          break;
        case kSampled:
        case kStorage:
          w[i].descriptorType =
              res[i].kind == kSampled ? VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
          ii[i].imageView = res[i].view;
          ii[i].imageLayout = res[i].layout;
          w[i].pImageInfo = &ii[i];
          break;
      }
    }
    UpdateDescriptorSets(dev, uint32_t(w.size()), w.data(), 0, nullptr);
  }

  // One dispatch. make(v) gives the bindings of variant v (v < 1, 3 or 2 by vary).
  bool Add(std::vector<Step>& prog, uint32_t id, VkExtent2D extent, int shift, Vary vary, bool barrier,
           const std::function<std::vector<Res>(int)>& make) {
    Step s{};
    s.vary = vary;
    s.barrier = barrier;
    s.gx = Groups(extent.width, shift);
    s.gy = Groups(extent.height, shift);
    const int n = vary == kFixed ? 1 : vary == kHistory ? 3 : 2;
    for (int v = 0; v < n; ++v) {
      std::vector<Res> res = make(v);
      if (!s.pipe && !(s.pipe = PipelineFor(id, res))) return false;
      s.sets[v] = NewSet(s.pipe->set_layout);
      if (!s.sets[v]) return false;
      Write(s.sets[v], res);
    }
    prog.push_back(s);
    return err.empty();
  }

  void Run(VkCommandBuffer cmd, const std::vector<Step>& prog, int history, int newest) {
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    const Pipeline* bound = nullptr;
    for (const Step& s : prog) {
      // Also before the first step: the last submission may still use our images.
      if (s.barrier || &s == &prog.front())
        CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1,
                           &mb, 0, nullptr, 0, nullptr);
      if (bound != s.pipe) CmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipe->pipeline);
      bound = s.pipe;
      const VkDescriptorSet set = s.sets[s.vary == kFixed ? 0 : s.vary == kHistory ? history : newest];
      CmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipe->layout, 0, 1, &set, 0, nullptr);
      CmdDispatch(cmd, s.gx, s.gy, 1);
    }
  }

  // All our images to GENERAL, the stand-in for absent inputs cleared to zero.
  void Initialize(VkCommandBuffer cmd, Image* zero) {
    std::vector<VkImageMemoryBarrier> b(images.size());
    for (size_t i = 0; i < images.size(); ++i) {
      b[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
      b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
      b[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
      b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      b[i].image = images[i]->image;
      b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    }
    CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                       VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                       nullptr, uint32_t(b.size()), b.data());
    const VkClearColorValue black{};
    const VkImageSubresourceRange all{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CmdClearColorImage(cmd, zero->image, VK_IMAGE_LAYOUT_GENERAL, &black, 1, &all);
    VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    CmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                       nullptr, 0, nullptr);
  }

  Image* zero = nullptr;
  bool Build(uint32_t width, uint32_t height, const VkImageView frames[2], VkImageView output);
};

bool Engine::Impl::Build(uint32_t width, uint32_t height, const VkImageView frames[2], VkImageView output) {
  sampler = NewSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
  white = NewSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER, VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE);
  edge = NewSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
  // Inputs the first pyramid level has no predecessor for read this (all zero, as a
  // null descriptor would).
  zero = NewImage({1, 1}, kMotion);
  if (!err.empty()) return false;

  auto U = [](VkBuffer b) { return Res{kUniform, b}; };
  auto S = [](VkSampler s) { return Res{kSampler, VK_NULL_HANDLE, s}; };
  auto T = [&](Image* im) { return Res{kSampled, VK_NULL_HANDLE, VK_NULL_HANDLE, (im ? im : zero)->view}; };
  auto F = [](VkImageView v) {  // a game frame: the wrapper keeps it read-only
    return Res{kSampled, VK_NULL_HANDLE, VK_NULL_HANDLE, v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
  };
  auto W = [](Image* im) { return Res{kStorage, VK_NULL_HANDLE, VK_NULL_HANDLE, im->view}; };
  using List = std::vector<Res>;

  // Flow pyramid: one pass makes all 7 levels from the newest frame.
  const VkExtent2D full{width, height};
  const VkExtent2D flow{std::max(1u, uint32_t(float(width) * flow_scale)),
                        std::max(1u, uint32_t(float(height) * flow_scale))};
  Image* mip[kLevels];
  for (int i = 0; i < kLevels; ++i)
    if (!(mip[i] = NewImage(Shift(flow, i), kFlow))) return false;
  const VkBuffer base = Uniform(0.f);
  if (!Add(shared, kMipmaps, flow, 6, kNewest, true, [&](int v) {
        List l{U(base), S(sampler), F(frames[v])};
        for (Image* m : mip) l.push_back(W(m));
        return l;
      }))
    return false;

  // Per level: four passes down to quarter size; the last keeps three frames of history.
  struct Alpha {
    Image *t1, *t2, *t3[2], *out[3][2];
  } alpha[kLevels];
  for (int l = 0; l < kLevels; ++l) {
    const VkExtent2D h = Half(mip[l]->extent), q = Half(h);
    Alpha& a = alpha[l];
    if (!(a.t1 = NewImage(h, kColor)) || !(a.t2 = NewImage(h, kColor))) return false;
    for (int j = 0; j < 2; ++j) {
      if (!(a.t3[j] = NewImage(q, kColor))) return false;
      for (int k = 0; k < 3; ++k)
        if (!(a.out[k][j] = NewImage(q, kColor))) return false;
    }
  }
  for (int stage = 0; stage < 4; ++stage) {
    for (int l = 0; l < kLevels; ++l) {
      Alpha& a = alpha[l];
      const VkExtent2D e = stage < 2 ? a.t1->extent : a.t3[0]->extent;
      const bool first = l == 0;  // levels of one stage do not depend on each other
      bool ok = true;
      switch (stage) {
        case 0:
          ok = Add(shared, kAlpha[0], e, 3, kFixed, first, [&](int) { return List{S(sampler), T(mip[l]), W(a.t1)}; });
          break;
        case 1:
          ok = Add(shared, kAlpha[1], e, 3, kFixed, first, [&](int) { return List{S(sampler), T(a.t1), W(a.t2)}; });
          break;
        case 2:
          ok = Add(shared, kAlpha[2], e, 3, kFixed, first,
                   [&](int) { return List{S(sampler), T(a.t2), W(a.t3[0]), W(a.t3[1])}; });
          break;
        case 3:
          ok = Add(shared, kAlpha[3], e, 3, kHistory, first, [&](int v) {
            return List{S(sampler), T(a.t3[0]), T(a.t3[1]), W(a.out[v][0]), W(a.out[v][1])};
          });
          break;
      }
      if (!ok) return false;
    }
  }
  auto hist = [&](int level, int k, List& l) {  // both images of one history slot
    l.push_back(T(alpha[level].out[k % 3][0]));
    l.push_back(T(alpha[level].out[k % 3][1]));
  };

  // Beta: on level 0's history, a 6-level output.
  const VkExtent2D be = alpha[0].out[0][0]->extent;
  Image *b1[2], *b2[2], *bout[kBetaOut];
  for (int j = 0; j < 2; ++j)
    if (!(b1[j] = NewImage(be, kColor)) || !(b2[j] = NewImage(be, kColor))) return false;
  for (int i = 0; i < kBetaOut; ++i)
    if (!(bout[i] = NewImage(Shift(be, i), kFlow))) return false;
  if (!Add(shared, kBeta[0], be, 3, kHistory, true, [&](int v) {
        List l{S(white)};
        hist(0, v + 1, l);
        hist(0, v + 2, l);
        hist(0, v, l);
        l.push_back(W(b1[0]));
        l.push_back(W(b1[1]));
        return l;
      }))
    return false;
  Image** ping[3] = {b1, b2, b1};
  Image** pong[3] = {b2, b1, b2};
  for (int i = 0; i < 3; ++i)
    if (!Add(shared, kBeta[1 + i], be, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(ping[i][0]), T(ping[i][1]), W(pong[i][0]), W(pong[i][1])};
        }))
      return false;
  const VkBuffer mid = Uniform(0.5f);
  if (!Add(shared, kBeta[4], be, 5, kFixed, true, [&](int) {
        List l{U(mid), S(sampler), T(b2[0]), T(b2[1])};
        for (Image* o : bout) l.push_back(W(o));
        return l;
      }))
    return false;

  // Gamma (all levels, coarse to fine) and delta (the three finest) refine the
  // motion for one generated frame's time; then the frame is made.
  struct Gamma {
    Image *t1[3], *t2[2], *out;
  } gamma[kLevels];
  struct Delta {
    Image *t1[3], *t2[2], *out1, *out2;
  } delta[3];
  for (int i = 0; i < kLevels; ++i) {
    const VkExtent2D e = alpha[kLevels - 1 - i].out[0][0]->extent;
    Gamma& g = gamma[i];
    for (auto*& t : g.t1)
      if (!(t = NewImage(e, kColor))) return false;
    for (auto*& t : g.t2)
      if (!(t = NewImage(e, kColor))) return false;
    if (!(g.out = NewImage(e, kMotion))) return false;
    if (i >= 4) {
      Delta& d = delta[i - 4];
      for (auto*& t : d.t1)
        if (!(t = NewImage(e, kColor))) return false;
      for (auto*& t : d.t2)
        if (!(t = NewImage(e, kColor))) return false;
      if (!(d.out1 = NewImage(e, kMotion)) || !(d.out2 = NewImage(e, kMotion))) return false;
    }
  }

  for (int count = 1; count <= kMaxGenerations; ++count) {
    for (int index = 0; index < count; ++index) {
      const int slot = SlotOf(count, index);
      const float ts = float(index + 1) / float(count + 1);
      std::vector<Step>& prog = generate[slot];
      for (int i = 0; i < kLevels; ++i) {
        const int level = kLevels - 1 - i;
        Gamma& g = gamma[i];
        Image* prev = i ? gamma[i - 1].out : nullptr;
        Image* flowin = bout[std::min(level, kBetaOut - 1)];
        const VkExtent2D e = g.out->extent;
        const VkBuffer ub = Uniform(ts, prev == nullptr);
        bool ok = Add(prog, kGamma[0], e, 3, kHistory, true, [&](int v) {
          List l{U(ub), S(white), S(edge)};
          hist(level, v + 2, l);
          hist(level, v, l);
          l.push_back(T(prev));
          for (Image* t : g.t1) l.push_back(W(t));
          return l;
        });
        ok = ok && Add(prog, kGamma[1], e, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(g.t1[0]), T(g.t1[1]), T(g.t1[2]), W(g.t2[0]), W(g.t2[1])};
        });
        ok = ok && Add(prog, kGamma[2], e, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(g.t2[0]), T(g.t2[1]), W(g.t1[0]), W(g.t1[1])};
        });
        ok = ok && Add(prog, kGamma[3], e, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(g.t1[0]), T(g.t1[1]), W(g.t2[0]), W(g.t2[1])};
        });
        ok = ok && Add(prog, kGamma[4], e, 3, kFixed, true, [&](int) {
          return List{U(ub), S(sampler), S(edge), T(g.t2[0]), T(g.t2[1]), T(prev), T(flowin), W(g.out)};
        });
        if (!ok) return false;
        if (i < 4) continue;

        const int j = i - 4;
        Delta& d = delta[j];
        Image* pg = j ? gamma[i - 1].out : nullptr;
        Image* p1 = j ? delta[j - 1].out1 : nullptr;
        Image* p2 = j ? delta[j - 1].out2 : nullptr;
        Image* dflow = bout[level];
        const VkBuffer dub = Uniform(ts, false, pg == nullptr);
        ok = Add(prog, kDelta[0], e, 3, kHistory, true, [&](int v) {
          List l{U(dub), S(white), S(edge)};
          hist(level, v + 2, l);
          hist(level, v, l);
          l.push_back(T(pg));
          for (Image* t : d.t1) l.push_back(W(t));
          return l;
        });
        ok = ok && Add(prog, kDelta[1], e, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(d.t1[0]), T(d.t1[1]), T(d.t1[2]), W(d.t2[0]), W(d.t2[1])};
        });
        ok = ok && Add(prog, kDelta[2], e, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(d.t2[0]), T(d.t2[1]), W(d.t1[0]), W(d.t1[1])};
        });
        ok = ok && Add(prog, kDelta[3], e, 3, kFixed, true, [&](int) {
          return List{S(sampler), T(d.t1[0]), T(d.t1[1]), W(d.t2[0]), W(d.t2[1])};
        });
        ok = ok && Add(prog, kDelta[4], e, 3, kFixed, true, [&](int) {
          return List{U(dub), S(sampler), S(edge), T(d.t2[0]), T(d.t2[1]), T(pg), T(dflow), W(d.out1)};
        });
        ok = ok && Add(prog, kDelta[5], e, 3, kHistory, true, [&](int v) {
          List l{U(dub), S(white), S(edge)};
          hist(level, v + 2, l);
          hist(level, v, l);
          l.push_back(T(pg));
          l.push_back(T(p1));
          l.push_back(W(d.t2[0]));
          return l;
        });
        ok = ok && Add(prog, kDelta[6], e, 3, kFixed, true, [&](int) { return List{S(sampler), T(d.t2[0]), W(d.t1[0])}; });
        ok = ok && Add(prog, kDelta[7], e, 3, kFixed, true, [&](int) { return List{S(sampler), T(d.t1[0]), W(d.t2[0])}; });
        ok = ok && Add(prog, kDelta[8], e, 3, kFixed, true, [&](int) { return List{S(sampler), T(d.t2[0]), W(d.t1[0])}; });
        ok = ok && Add(prog, kDelta[9], e, 3, kFixed, true, [&](int) {
          return List{U(dub), S(sampler), S(edge), T(d.t1[0]), T(p2), W(d.out2)};
        });
        if (!ok) return false;
      }
      const VkBuffer gub = Uniform(ts);
      if (!Add(prog, kGenerate, full, 4, kNewest, true, [&](int v) {
            return List{U(gub),        S(sampler),          S(edge),           F(frames[1 - v]), F(frames[v]),
                        T(gamma[kLevels - 1].out), T(delta[2].out1), T(delta[2].out2),
                        Res{kStorage, VK_NULL_HANDLE, VK_NULL_HANDLE, output}};
          }))
        return false;
    }
  }
  return err.empty();
}

std::unique_ptr<Engine> Engine::Create(VkPhysicalDevice, VkDevice device, PFN_vkGetDeviceProcAddr gdpa,
                                       const VkPhysicalDeviceMemoryProperties& mem, const ShaderPack& pack,
                                       uint32_t width, uint32_t height, const VkImageView frames[2],
                                       VkImageView output, float flow_scale, std::string* err) {
  auto impl = std::make_unique<Impl>();
  impl->dev = device;
  impl->mem = mem;
  impl->pack = &pack;
  impl->flow_scale = std::clamp(flow_scale, 0.25f, 1.f);
  bool missing = false;
#define LSFG_LOAD(n)                                                     \
  impl->n = reinterpret_cast<PFN_vk##n>(gdpa(device, "vk" #n));          \
  missing |= impl->n == nullptr;
  LSFG_FNS(LSFG_LOAD)
#undef LSFG_LOAD
  if (missing) {
    *err = "device lacks entry points";
    impl->dev = VK_NULL_HANDLE;
    return nullptr;
  }
  if (!impl->Build(width, height, frames, output)) {
    *err = impl->err.empty() ? "setup failed" : impl->err;
    return nullptr;
  }
  auto e = std::unique_ptr<Engine>(new Engine());
  e->impl_ = std::move(impl);
  return e;
}

Engine::~Engine() = default;

void Engine::Push(VkCommandBuffer cmd, int cur, bool reset) {
  if (!impl_->initialized) {
    impl_->Initialize(cmd, impl_->zero);
    impl_->initialized = true;
  }
  if (reset) frame_ = warm_ = 0;
  const uint64_t f = frame_++;
  // The first frame has nothing to compare with; from the second on the shared
  // passes run, and generation needs two of them in a row (history).
  if (f == 0) {
    warm_ = 0;
    return;
  }
  impl_->Run(cmd, impl_->shared, int(f % 3), cur);
  ++warm_;
}

void Engine::Generate(VkCommandBuffer cmd, int cur, int count, int index) {
  count = std::clamp(count, 1, kMaxGenerations);
  index = std::clamp(index, 0, count - 1);
  impl_->Run(cmd, impl_->generate[SlotOf(count, index)], int((frame_ - 1) % 3), cur);
}

}  // namespace lsfg
