// LSFG-style frame generation: Lossless Scaling's frame generation shaders, taken
// from the user's own Lossless.dll (the app extracts them into a shader pack), run
// on the game's two latest frames. Written from scratch for this wrapper; the pass
// graph (which shader reads which images) follows the public lsfg-vk / Eden design.
#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

namespace lsfg {

// Shader id -> SPIR-V words, as Lossless.dll numbers its resources.
using ShaderPack = std::map<uint32_t, std::vector<uint32_t>>;

// Reads the app's pack file ("LSFP", count, then id, word count, words).
bool LoadPack(const char* path, ShaderPack* out, std::string* err);

// SPIR-V capabilities the pack's shaders declare (OpCapability operands), so the
// device can be created with the matching features.
std::set<uint32_t> Capabilities(const ShaderPack& pack);

constexpr int kMaxGenerations = 3;  // 4x

class Engine {
 public:
  // frames[0], frames[1]: the wrapper's two game-frame copies (sampled usage, kept
  // in SHADER_READ_ONLY_OPTIMAL). The output image must allow storage writes.
  // flow_scale: optical flow resolution (0.25 .. 1).
  static std::unique_ptr<Engine> Create(VkPhysicalDevice phys, VkDevice device, PFN_vkGetDeviceProcAddr gdpa,
                                        const VkPhysicalDeviceMemoryProperties& mem, const ShaderPack& pack,
                                        uint32_t width, uint32_t height, const VkImageView frames[2],
                                        VkImageView output, float flow_scale, std::string* err);
  ~Engine();

  // A new game frame was copied into frames[cur]. Runs the per-frame work (flow
  // pyramid and feature passes). reset: the frame does not follow the last one.
  void Push(VkCommandBuffer cmd, int cur, bool reset);

  // Enough history for generation.
  bool Ready() const { return warm_ >= 2; }

  // Generated frame `index` (0 .. count - 1) of `count` (1 .. kMaxGenerations)
  // between the previous frame and frames[cur], at time (index + 1) / (count + 1), into the output
  // image, which must be in GENERAL. Call after Push for this frame.
  void Generate(VkCommandBuffer cmd, int cur, int count, int index);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
  uint64_t frame_ = 0;  // frames pushed since the last reset
  int warm_ = 0;
};

}  // namespace lsfg
