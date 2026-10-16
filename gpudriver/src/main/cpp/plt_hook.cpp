#include "plt_hook.h"

#include <android/log.h>
#include <elf.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstring>

#define TAG "BBGpu"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__aarch64__)
#define BB_R_JUMP_SLOT R_AARCH64_JUMP_SLOT
#define BB_R_GLOB_DAT  R_AARCH64_GLOB_DAT
#elif defined(__x86_64__)
#define BB_R_JUMP_SLOT R_X86_64_JUMP_SLOT
#define BB_R_GLOB_DAT  R_X86_64_GLOB_DAT
#else
#error "plt_hook supports 64-bit RELA targets only"
#endif

namespace {

struct Request {
    const char *basename;
    PltHook *hooks;
    size_t count;
    bool *applied;
};

const char *baseName(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash != nullptr ? slash + 1 : path;
}

// Writes `value` into a GOT slot, temporarily lifting RELRO protection.
bool writeSlot(void **slot, void *value, uintptr_t relroStart, uintptr_t relroEnd) {
    const uintptr_t pageSize = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
    const uintptr_t addr = reinterpret_cast<uintptr_t>(slot);
    void *page = reinterpret_cast<void *>(addr & ~(pageSize - 1));
    // The linker protects RELRO in whole pages (end rounded up), so compare pages.
    const uintptr_t relroPageStart = relroStart & ~(pageSize - 1);
    const uintptr_t relroPageEnd = (relroEnd + pageSize - 1) & ~(pageSize - 1);
    const bool inRelro = relroEnd > relroStart && addr >= relroPageStart && addr < relroPageEnd;
    if (inRelro && mprotect(page, pageSize, PROT_READ | PROT_WRITE) != 0) {
        LOGE("mprotect(RW) failed for GOT slot %p", slot);
        return false;
    }
    __atomic_store_n(slot, value, __ATOMIC_SEQ_CST);
    if (inRelro) {
        mprotect(page, pageSize, PROT_READ);
    }
    return true;
}

// Scans one relocation table and patches the slots of the requested symbols.
void patchTable(const Request &req, uintptr_t base, const ElfW(Rela) *rela, size_t size,
                const ElfW(Sym) *symtab, const char *strtab, uintptr_t relroStart,
                uintptr_t relroEnd) {
    const size_t entries = size / sizeof(ElfW(Rela));
    for (size_t i = 0; i < entries; ++i) {
        const auto type = ELF64_R_TYPE(rela[i].r_info);
        if (type != BB_R_JUMP_SLOT && type != BB_R_GLOB_DAT) {
            continue;
        }
        const char *name = strtab + symtab[ELF64_R_SYM(rela[i].r_info)].st_name;
        for (size_t h = 0; h < req.count; ++h) {
            PltHook &hook = req.hooks[h];
            if (strcmp(name, hook.symbol) != 0) {
                continue;
            }
            void **slot = reinterpret_cast<void **>(base + rela[i].r_offset);
            void *previous = *slot;
            if (previous == hook.replacement) {
                req.applied[h] = true;  // already patched
                continue;
            }
            if (writeSlot(slot, hook.replacement, relroStart, relroEnd)) {
                if (hook.original != nullptr && *hook.original == nullptr) {
                    *hook.original = previous;
                }
                req.applied[h] = true;
            }
        }
    }
}

int onLibrary(struct dl_phdr_info *info, size_t, void *data) {
    const auto &req = *static_cast<Request *>(data);
    if (info->dlpi_name == nullptr || strcmp(baseName(info->dlpi_name), req.basename) != 0) {
        return 0;
    }
    const uintptr_t base = info->dlpi_addr;
    const ElfW(Dyn) *dynamic = nullptr;
    uintptr_t relroStart = 0;
    uintptr_t relroEnd = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &phdr = info->dlpi_phdr[i];
        if (phdr.p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn) *>(base + phdr.p_vaddr);
        } else if (phdr.p_type == PT_GNU_RELRO) {
            relroStart = base + phdr.p_vaddr;
            relroEnd = relroStart + phdr.p_memsz;
        }
    }
    if (dynamic == nullptr) {
        return 0;
    }

    const ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    const ElfW(Rela) *jmprel = nullptr;
    size_t jmprelSize = 0;
    const ElfW(Rela) *rela = nullptr;
    size_t relaSize = 0;
    bool pltIsRela = true;
    for (const ElfW(Dyn) *d = dynamic; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
            case DT_SYMTAB: symtab = reinterpret_cast<const ElfW(Sym) *>(base + d->d_un.d_ptr); break;
            case DT_STRTAB: strtab = reinterpret_cast<const char *>(base + d->d_un.d_ptr); break;
            case DT_JMPREL: jmprel = reinterpret_cast<const ElfW(Rela) *>(base + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: jmprelSize = d->d_un.d_val; break;
            case DT_PLTREL: pltIsRela = d->d_un.d_val == DT_RELA; break;
            case DT_RELA: rela = reinterpret_cast<const ElfW(Rela) *>(base + d->d_un.d_ptr); break;
            case DT_RELASZ: relaSize = d->d_un.d_val; break;
            default: break;
        }
    }
    if (symtab == nullptr || strtab == nullptr || !pltIsRela) {
        LOGE("%s: unsupported dynamic section", info->dlpi_name);
        return 0;
    }
    // Calls go through DT_JMPREL (never Android-packed). DT_RELA covers imports
    // whose address is taken; packed DT_ANDROID_RELA entries are not handled.
    if (jmprel != nullptr) {
        patchTable(req, base, jmprel, jmprelSize, symtab, strtab, relroStart, relroEnd);
    }
    if (rela != nullptr) {
        patchTable(req, base, rela, relaSize, symtab, strtab, relroStart, relroEnd);
    }
    LOGI("patched PLT of %s", info->dlpi_name);
    return 0;  // keep going: a process may map more than one copy
}

}  // namespace

size_t PatchPlt(const char *libBasename, PltHook *hooks, size_t count) {
    bool applied[16] = {};
    if (count > sizeof(applied) / sizeof(applied[0])) {
        return 0;
    }
    Request req{libBasename, hooks, count, applied};
    dl_iterate_phdr(onLibrary, &req);
    size_t done = 0;
    for (size_t i = 0; i < count; ++i) {
        if (applied[i]) {
            ++done;
        } else {
            LOGE("no GOT slot for %s in %s", hooks[i].symbol, libBasename);
        }
    }
    return done;
}
