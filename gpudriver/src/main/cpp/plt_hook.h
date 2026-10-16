#pragma once

#include <cstddef>

// Minimal PLT/GOT patcher: rewrites the GOT slots through which an already
// loaded library calls the named imports. Enough for redirecting libdl.so's
// calls into the linker; not a general hooking framework (no chaining, no
// handling of libraries loaded later).
struct PltHook {
    const char *symbol;  // imported symbol name, e.g. "__loader_dlopen"
    void *replacement;   // function to install in the caller's GOT slot
    void **original;     // receives the previous slot value (first patched copy)
};

// Patches `hooks` in every loaded library whose file name is `libBasename`.
// Returns the number of hooks that were applied at least once.
size_t PatchPlt(const char *libBasename, PltHook *hooks, size_t count);
