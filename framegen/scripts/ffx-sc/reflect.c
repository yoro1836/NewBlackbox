// Prints a SPIR-V module's descriptor bindings the way FidelityFX-SC's glslang
// path classifies them: "<kind> <name> <binding> <count> <set>" per line.
#include <stdio.h>
#include <stdlib.h>
#include "spirv_reflect.h"

int main(int argc, char** argv) {
  if (argc != 2) return 2;
  FILE* f = fopen(argv[1], "rb");
  if (!f) return 1;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  void* buf = malloc(n);
  if (fread(buf, 1, n, f) != (size_t)n) return 1;
  fclose(f);
  SpvReflectShaderModule m;
  if (spvReflectCreateShaderModule(n, buf, &m) != SPV_REFLECT_RESULT_SUCCESS) return 1;
  uint32_t count = 0;
  spvReflectEnumerateDescriptorSets(&m, &count, NULL);
  SpvReflectDescriptorSet** sets = calloc(count ? count : 1, sizeof *sets);
  spvReflectEnumerateDescriptorSets(&m, &count, sets);
  for (uint32_t s = 0; s < count; ++s) {
    for (uint32_t b = 0; b < sets[s]->binding_count; ++b) {
      SpvReflectDescriptorBinding* d = sets[s]->bindings[b];
      const char* kind;
      switch (d->descriptor_type) {
        case SPV_REFLECT_DESCRIPTOR_TYPE_UNIFORM_BUFFER: kind = "CBV"; break;
        case SPV_REFLECT_DESCRIPTOR_TYPE_SAMPLED_IMAGE: kind = "TextureSRV"; break;
        case SPV_REFLECT_DESCRIPTOR_TYPE_SAMPLER: kind = "Sampler"; break;
        case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_IMAGE: kind = "TextureUAV"; break;
        case SPV_REFLECT_DESCRIPTOR_TYPE_STORAGE_BUFFER:
          kind = d->resource_type == SPV_REFLECT_RESOURCE_FLAG_SRV ? "BufferSRV" : "BufferUAV"; break;
        case SPV_REFLECT_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR: kind = "RTAccelerationStructure"; break;
        default: fprintf(stderr, "unsupported descriptor type %d\n", d->descriptor_type); return 1;
      }
      printf("%s %s %u %u %u\n", kind, d->name && *d->name ? d->name : "-", d->binding, d->count, sets[s]->set);
    }
  }
  return 0;
}
