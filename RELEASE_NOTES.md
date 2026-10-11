# Release Notes - NewBlackbox

## GMS emulator compatibility (2026-09-19)

- Added a native x86_64 build for emulator testing without ARM translation.
- Show User 0 in GMS Manager before any virtual users have been created.
- Report missing required Google packages and failed installation responses instead of success.
- Never treat a host-installed package as installed in a virtual user when the package service is unavailable.
- Retain the service provider connection so Android does not freeze the virtual server while foreground clients use its binders.
- Install ContentProviders only in their declared process or when marked multiprocess.
- Translate broadcast and application-restriction queries to the host Android user.
- Cover Android 16's device-aware account permission check.

Verification: Android 16 Google Play x86_64 emulator imports GMS, GSF, and Play Store; Play Store reaches its sign-in landing page. Account creation remains blocked by Google Play services 26.34.36 with `Only 0p apps are allowed to call this API.` This is not a claim of working Google login or complete GMS compatibility.

## Version: Latest Build (2026-01-31)

---

### New Features

#### VPN Network Mode Toggle
Added a new setting to choose between VPN and normal network mode for sandboxed apps.

- **Location:** Settings → Others → Use VPN Network
- **Default:** OFF (normal network mode)
- When enabled, traffic is routed through BlackBox's VPN service
- Requires app restart to take effect

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/BlackBoxLoader.kt`
- `app/src/main/java/top/niunaijun/blackboxa/view/setting/SettingFragment.kt`
- `app/src/main/res/xml/setting.xml`
- `app/src/main/res/values/strings.xml`
- `Bcore/src/main/java/top/niunaijun/blackbox/app/configuration/ClientConfiguration.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/BlackBoxCore.java`

#### Device Information Logging
Added comprehensive device info header in logcat for easier debugging:
- Android version, SDK level, security patch
- Device manufacturer, brand, model, hardware
- Supported CPU/ABIs (32-bit and 64-bit)
- Memory info (heap usage)
- App version and package info
- Build fingerprint and timestamps

---

### Bug Fixes

#### VPN Permission Fix
**Problem:** VPN service failed to establish interface (`builder.establish()` returned null).

**Root Cause:** Android requires `VpnService.prepare()` to be called from an Activity before VPN can be established.

**Solution:** Added VPN permission request to `MainActivity.kt` on app launch.

**Files Changed:**
- `app/src/main/java/top/niunaijun/blackboxa/view/main/MainActivity.kt`

---

#### Android 10 Black Screen Fix
**Problem:** Apps would show a black screen and timeout on Android 10 (API 29).

**Root Cause:** 
- `BRAttributionSource.getRealClass()` returns `null` on Android < 31
- `SystemProviderStub.invoke()` crashed calling `.getName()` on null class
- `ClassInvocationStub.injectHook()` crashed when `getWho()` returned null

**Solution:**
- Added null checks in `SystemProviderStub.java` for API version checks
- Added null check in `ClassInvocationStub.java` to skip hooks when services don't exist

**Files Changed:**
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/service/context/providers/SystemProviderStub.java`
- `Bcore/src/main/java/top/niunaijun/blackbox/fake/hook/ClassInvocationStub.java`

---

### Removed Features

#### Xposed Framework Support
- Removed `BXposedManagerService` and related AIDL interfaces
- Removed "Install Xposed Module" UI and Settings entries
- Cleaned up Xposed-related flags and package checks

---

### Stability Improvements

#### Anti-Detection Native Hook Stability
- Removed `LOGD` calls from critical native hooks to prevent infinite recursion
- Fixed syntax errors in hook implementations
- Hooks now silently return `ENOENT` for blocked paths

---

### Known Issues

#### Oppo/ColorOS Thermal Stats Error
On Oppo/ColorOS devices, you may see errors like:
```
OppoThermalStats: PackageManager$NameNotFoundException: top.niunaijun.blackboxa:p0
```
**This is harmless** - it's an Oppo system bug where their thermal management incorrectly uses process names (with `:p0` suffix) instead of package names. The app works normally.

---

### Compatibility

| Android Version | Status |
|-----------------|--------|
| Android 10 (Q)  | ✅ Fixed |
| Android 11 (R)  | ✅ Supported |
| Android 12 (S)  | ✅ Supported |
| Android 13 (T)  | ✅ Supported |
| Android 14 (U)  | ✅ Supported |
| Android 15+     | ✅ Supported |
