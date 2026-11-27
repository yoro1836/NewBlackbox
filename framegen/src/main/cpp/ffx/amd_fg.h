// AMD FidelityFX frame generation without game data: FSR 3.1's optical flow
// and frame interpolation, fed only the presented colour (zero motion vectors,
// no depth). That is the same idea as AMD's driver-level AFMF: the in-between
// frame comes from the two finished frames alone.
#pragma once

#include <vulkan/vulkan.h>

#include <cstdint>
#include <memory>
#include <string>

namespace amdfg {

class Engine {
 public:
  // Fails (nullptr, reason in *err) when the device or format is unsupported.
  // Frames are width x height; the optical flow runs on a copy of each at
  // of_width x of_height (0: the same size), the generated frame is full size.
  static std::unique_ptr<Engine> Create(VkPhysicalDevice phys, VkDevice device, PFN_vkGetDeviceProcAddr gdpa,
                                        uint32_t width, uint32_t height, VkFormat format, std::string* err,
                                        uint32_t of_width = 0, uint32_t of_height = 0);
  ~Engine();

  // Records into cmd the frame halfway between the previous call's `current`
  // and this one's. `current` must be in SHADER_READ_ONLY_OPTIMAL (sampled
  // usage), `output` in GENERAL (storage usage); both are left that way. The
  // first call after a reset outputs a copy of `current`. Returns false on an
  // FFX error. With extrapolate, the frame is the one half a frame after
  // `current` instead (built from the same two frames' motion).
  // of_current is current at the optical flow size (the same image when that is
  // the full size), also in SHADER_READ_ONLY_OPTIMAL.
  bool Generate(VkCommandBuffer cmd, VkImage current, VkImage of_current, VkImage output, bool reset,
                float frame_ms, bool extrapolate = false);

  // GPU time per FFX pass, sampled every `every` calls (0: off). ts_period is the
  // device's timestampPeriod (ns per tick); the queue family must have timestamps.
  void Profile(float ts_period, int every);
  // "name ms, ..." from the latest sampled call, once its results are in; else "".
  std::string TakeProfile();

  struct Impl;

 private:
  explicit Engine(std::unique_ptr<Impl> impl);
  std::unique_ptr<Impl> impl_;
};

}  // namespace amdfg
