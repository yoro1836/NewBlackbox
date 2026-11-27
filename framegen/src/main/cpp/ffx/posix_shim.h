// The FidelityFX SDK host code uses a few MSVC-only CRT functions; this makes
// them available on Android/Linux. Force-included into every FidelityFX file.
#pragma once
#ifndef _WIN32
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <math.h>
#ifdef __cplusplus
template <typename T, size_t N>
constexpr size_t _countof_impl(T (&)[N]) { return N; }
#define _countof(a) _countof_impl(a)
template <size_t N>
inline int wcscpy_s(wchar_t (&dst)[N], const wchar_t* src) {
  wcsncpy(dst, src, N - 1);
  dst[N - 1] = 0;
  return 0;
}
inline int wcscpy_s(wchar_t* dst, size_t n, const wchar_t* src) {
  wcsncpy(dst, src, n - 1);
  dst[n - 1] = 0;
  return 0;
}
template <size_t N>
inline int strcpy_s(char (&dst)[N], const char* src) {
  strncpy(dst, src, N - 1);
  dst[N - 1] = 0;
  return 0;
}
inline int strcpy_s(char* dst, size_t n, const char* src) {
  strncpy(dst, src, n - 1);
  dst[n - 1] = 0;
  return 0;
}
inline int wcstombs_s(size_t* ret, char* dst, size_t n, const wchar_t* src, size_t max) {
  size_t r = wcstombs(dst, src, (n < max ? n : max) - 1);
  if (r == (size_t)-1) r = 0;
  dst[r] = 0;
  if (ret) *ret = r + 1;
  return 0;
}
inline int mbstowcs_s(size_t* ret, wchar_t* dst, size_t n, const char* src, size_t max) {
  size_t r = mbstowcs(dst, src, (n < max ? n : max) - 1);
  if (r == (size_t)-1) r = 0;
  dst[r] = 0;
  if (ret) *ret = r + 1;
  return 0;
}
#endif
#endif
#if !defined(_WIN32)
#include <stdio.h>
#define sprintf_s snprintf
#endif
