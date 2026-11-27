// Host test: runs the LSFG engine on lavapipe with a synthetic forward-zoom scene
// (like driving forward) and measures the generated frame against the true middle.
#include <vulkan/vulkan.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>
#include <functional>

#include "lsfg/lsfg.h"

#define CK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { printf("%s failed %d\n", #x, r_); exit(1); } } while (0)

static VkDevice dev;
static VkPhysicalDevice phys;
static VkPhysicalDeviceMemoryProperties memp;
static VkQueue q;
static VkCommandPool pool;
static const int W = 960, H = 540;

uint32_t MemType(uint32_t bits, VkMemoryPropertyFlags f) {
  for (uint32_t i = 0; i < memp.memoryTypeCount; ++i)
    if ((bits & (1u << i)) && (memp.memoryTypes[i].propertyFlags & f) == f) return i;
  return 0;
}
struct Img { VkImage im; VkDeviceMemory m; VkImageView v; };
Img MakeImg(VkImageUsageFlags u) {
  Img r{};
  VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  ci.imageType = VK_IMAGE_TYPE_2D; ci.format = VK_FORMAT_R8G8B8A8_UNORM; ci.extent = {W, H, 1};
  ci.mipLevels = ci.arrayLayers = 1; ci.samples = VK_SAMPLE_COUNT_1_BIT; ci.usage = u;
  CK(vkCreateImage(dev, &ci, nullptr, &r.im));
  VkMemoryRequirements mr; vkGetImageMemoryRequirements(dev, r.im, &mr);
  VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, MemType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)};
  CK(vkAllocateMemory(dev, &ai, nullptr, &r.m)); CK(vkBindImageMemory(dev, r.im, r.m, 0));
  VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  vi.image = r.im; vi.viewType = VK_IMAGE_VIEW_TYPE_2D; vi.format = ci.format; vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  CK(vkCreateImageView(dev, &vi, nullptr, &r.v));
  return r;
}
VkBuffer stage; VkDeviceMemory stage_m; uint8_t* stage_p;
void Barrier(VkCommandBuffer c, VkImage im, VkImageLayout a, VkImageLayout b) {
  VkImageMemoryBarrier m{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  m.srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT; m.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
  m.oldLayout = a; m.newLayout = b; m.srcQueueFamilyIndex = m.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  m.image = im; m.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr, 0, nullptr, 1, &m);
}
void Submit(const std::function<void(VkCommandBuffer)>& f) {
  VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, nullptr, pool, VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
  VkCommandBuffer c; CK(vkAllocateCommandBuffers(dev, &ai, &c));
  VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
  vkBeginCommandBuffer(c, &bi); f(c); vkEndCommandBuffer(c);
  VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &c;
  CK(vkQueueSubmit(q, 1, &si, VK_NULL_HANDLE)); vkQueueWaitIdle(q);
  vkFreeCommandBuffers(dev, pool, 1, &c);
}
void Upload(Img& im, const std::vector<uint8_t>& px, VkImageLayout from) {
  memcpy(stage_p, px.data(), px.size());
  Submit([&](VkCommandBuffer c) {
    Barrier(c, im.im, from, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkBufferImageCopy r{}; r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; r.imageExtent = {W, H, 1};
    vkCmdCopyBufferToImage(c, stage, im.im, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    Barrier(c, im.im, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  });
}
std::vector<uint8_t> Download(Img& im) {
  Submit([&](VkCommandBuffer c) {
    Barrier(c, im.im, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    VkBufferImageCopy r{}; r.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; r.imageExtent = {W, H, 1};
    vkCmdCopyImageToBuffer(c, im.im, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, stage, 1, &r);
    Barrier(c, im.im, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL);
  });
  return std::vector<uint8_t>(stage_p, stage_p + W * H * 4);
}

// Scene: a procedural texture seen through a camera moving forward (zoom about
// the centre, faster each frame toward the edges), plus a road-like stripe.
float Hash(int x, int y) { uint32_t h = x * 374761393u + y * 668265263u; h = (h ^ (h >> 13)) * 1274126177u; return (h ^ (h >> 16)) / 4294967295.f; }
float Noise(float x, float y) {
  int xi = (int)floorf(x), yi = (int)floorf(y); float fx = x - xi, fy = y - yi;
  fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy);
  float a = Hash(xi, yi), b = Hash(xi + 1, yi), c = Hash(xi, yi + 1), d = Hash(xi + 1, yi + 1);
  return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
}
float g_zoom_per_frame = 1.06f;
void Pixel(float sx, float sy, float tau, uint8_t* out) {
  float z = powf(g_zoom_per_frame, tau);
  float x = (sx - W / 2.f) / z, y = (sy - H / 2.f) / z;
  float n1 = Noise(x / 24.f, y / 24.f), n2 = Noise(x / 7.f + 50, y / 7.f), n3 = Noise(x / 2.5f, y / 2.5f + 90);
  float stripes = (fmodf(fabsf(x) / 40.f + fabsf(y) / 90.f, 1.f) < 0.15f) ? 0.6f : 0.f;
  float r = 0.5f * n1 + 0.3f * n2 + 0.2f * n3 + stripes, g = 0.6f * n2 + 0.4f * n3, b = 0.7f * n1 + 0.3f * Noise(x / 13.f, y / 13.f + 7);
  out[0] = (uint8_t)std::clamp(r * 255.f, 0.f, 255.f); out[1] = (uint8_t)std::clamp(g * 255.f, 0.f, 255.f);
  out[2] = (uint8_t)std::clamp(b * 255.f, 0.f, 255.f); out[3] = 255;
  if (getenv("PARALLAX") && *getenv("PARALLAX")) {
    // Roadside posts, closer than the background: they zoom faster.
    float zf = powf(g_zoom_per_frame, 1.8f * tau) * 0.8f;
    float fx = (sx - W / 2.f) / zf, fy = (sy - H / 2.f) / zf;
    float cell = fmodf(fabsf(fx) + 1000.f, 70.f);
    if (fabsf(fx) > 150.f && cell < 12.f && fy > -120.f) {
      float shade = 0.5f + 0.5f * Noise(fx / 3.f, fy / 3.f);
      out[0] = (uint8_t)(230 * shade); out[1] = (uint8_t)(220 * shade); out[2] = (uint8_t)(60 * shade);
    }
  }
}
std::vector<uint8_t> Frame(float tau) {
  std::vector<uint8_t> px(W * H * 4);
  for (int y = 0; y < H; ++y)
    for (int x = 0; x < W; ++x) {
      // 2x2 supersample like a rendered frame
      int acc[4] = {};
      for (int s = 0; s < 4; ++s) { uint8_t p[4]; Pixel(x + 0.25f + 0.5f * (s & 1), y + 0.25f + 0.5f * (s >> 1), tau, p); for (int k = 0; k < 4; ++k) acc[k] += p[k]; }
      for (int k = 0; k < 4; ++k) px[(y * W + x) * 4 + k] = acc[k] / 4;
    }
  return px;
}
void WritePpm(const char* path, const std::vector<uint8_t>& px) {
  FILE* f = fopen(path, "wb"); fprintf(f, "P6\n%d %d\n255\n", W, H);
  for (int i = 0; i < W * H; ++i) fwrite(&px[i * 4], 1, 3, f); fclose(f);
}
// Mean abs error in the central region and the outer band (by distance to the edge).
void Score(const char* name, const std::vector<uint8_t>& a, const std::vector<uint8_t>& b) {
  double e[3] = {}; long n[3] = {};
  for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
    float d = std::min({x / float(W), (W - 1 - x) / float(W), y / float(H), (H - 1 - y) / float(H)});
    int r = d < 0.05f ? 2 : d < 0.15f ? 1 : 0;
    for (int k = 0; k < 3; ++k) e[r] += abs(a[(y * W + x) * 4 + k] - b[(y * W + x) * 4 + k]);
    n[r] += 3;
  }
  printf("%-22s centre %6.2f  mid %6.2f  edge(5%%) %6.2f\n", name, e[0] / n[0], e[1] / n[1], e[2] / n[2]);
}

int main(int argc, char** argv) {
  const char* packpath = argv[1];
  float flow = argc > 2 ? atof(argv[2]) : 1.f;
  g_zoom_per_frame = argc > 3 ? atof(argv[3]) : 1.06f;
  const char* outdir = argc > 4 ? argv[4] : nullptr;
  lsfg::ShaderPack pack; std::string err;
  if (!lsfg::LoadPack(packpath, &pack, &err)) { printf("pack: %s\n", err.c_str()); return 1; }

  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_3;
  VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
  VkInstance inst; CK(vkCreateInstance(&ici, nullptr, &inst));
  uint32_t n = 1; vkEnumeratePhysicalDevices(inst, &n, &phys);
  vkGetPhysicalDeviceMemoryProperties(phys, &memp);
  float prio = 1;
  VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qci.queueCount = 1; qci.pQueuePriorities = &prio;
  VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES}; f12.shaderFloat16 = VK_TRUE;
  VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &f12};
  f2.features.shaderStorageImageExtendedFormats = VK_TRUE; f2.features.shaderStorageImageWriteWithoutFormat = VK_TRUE;
  VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, &f2}; dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
  CK(vkCreateDevice(phys, &dci, nullptr, &dev));
  vkGetDeviceQueue(dev, 0, 0, &q);
  VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; CK(vkCreateCommandPool(dev, &pci, nullptr, &pool));
  VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bci.size = W * H * 4; bci.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  CK(vkCreateBuffer(dev, &bci, nullptr, &stage));
  VkMemoryRequirements mr; vkGetBufferMemoryRequirements(dev, stage, &mr);
  VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, nullptr, mr.size, MemType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)};
  CK(vkAllocateMemory(dev, &mai, nullptr, &stage_m)); vkBindBufferMemory(dev, stage, stage_m, 0);
  vkMapMemory(dev, stage_m, 0, VK_WHOLE_SIZE, 0, (void**)&stage_p);

  Img fr[2] = {MakeImg(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT), MakeImg(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)};
  Img out = MakeImg(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
  Submit([&](VkCommandBuffer c) { Barrier(c, out.im, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL); });
  const VkImageView views[2] = {fr[0].v, fr[1].v};

  const int frames = 5;
  std::vector<std::vector<uint8_t>> real;
  for (int i = 0; i < frames; ++i) real.push_back(Frame(float(i)));
  const auto truth = Frame(frames - 1.5f);
  for (int mode = 0; mode < 2; ++mode) {
    auto eng = lsfg::Engine::Create(phys, dev, vkGetDeviceProcAddr, memp, pack, W, H, views, out.v, flow, mode == 1, &err);
    if (!eng) { printf("engine: %s\n", err.c_str()); return 1; }
    bool init[2] = {};
    for (int i = 0; i < frames; ++i) {
      int cur = i & 1;
      Upload(fr[cur], real[i], init[cur] ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED);
      init[cur] = true;
      Submit([&](VkCommandBuffer c) {
        eng->Push(c, cur, false);
        if (i == frames - 1 && eng->Ready()) eng->Generate(c, cur, 1, 0);
      });
    }
    auto gen = Download(out);
    Score(mode ? "ours (edge fix)" : "pack generate", gen, truth);
    if (outdir) { char p[512]; snprintf(p, sizeof p, "%s/gen%d.ppm", outdir, mode); WritePpm(p, gen); }
  }
  // Baselines: blend of the two frames, and the newer frame.
  std::vector<uint8_t> blend(W * H * 4);
  for (size_t i = 0; i < blend.size(); ++i) blend[i] = (real[frames - 2][i] + real[frames - 1][i]) / 2;
  Score("blend (no motion)", blend, truth);
  Score("newer frame", real[frames - 1], truth);
  if (outdir) { char p[512]; snprintf(p, sizeof p, "%s/truth.ppm", outdir); WritePpm(p, truth); }
  return 0;
}
