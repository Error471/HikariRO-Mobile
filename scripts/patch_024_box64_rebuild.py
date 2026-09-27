from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2400")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.24.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.23", "0.24"), encoding="utf-8")

# 0.24 does not change any Java/env-var logic -- the WINELOADERNOEXEC=1 /
# WINEARCH=wow64 / BOX64_MMAP32=1 / BOX64_RESERVE_HIGH=1 combination from 0.23
# is left exactly as-is (it may still be harmless/beneficial alongside a fixed
# Box64, and there is no reason to touch it).
#
# What 0.24 actually changes lives entirely in the CI workflow, not here: the
# real root cause found via 0.23's own on-device trace log
# (hikari-startup-023.log / box64-trace-023.txt, both analyzed directly) is
# that the *bundled Box64 binary itself* (v0.4.4, built 2026-08-03) never
# publishes the `wine_main_preload_info` symbol Wine's ntdll looks up with
# dlsym(NULL, ...) -- Box64 substitutes its own hardcoded 3-range address
# "prereserve" for Wine's real preloader stub, but doesn't export that symbol,
# so ntdll falls back to a fresh NtAllocateVirtualMemory at the fixed
# KUSER_SHARED_DATA address 0x7ffe0000 -- which lands squarely inside Box64's
# own third prereserve range [0x7f000000, 0x82000000), producing
# STATUS_CONFLICTING_ADDRESSES (c0000018). This is a Box64-side bug, not
# something any Wine env var can route around.
#
# Upstream ptitSeb/box64 has moved six weeks past our bundled build since
# then, including PR #4377 (merged 2026-09-14, "Limit the maximum length of
# mmap with MAP_NORESERVE to 96GiB on 39-bit platforms") -- directly relevant
# since our own box64 trace log shows "Didn't detect 48bits of address space,
# considering it's 39bits" on this device. The new
# "Cross-compile fresh Box64 from source" workflow step (added right after
# checking out winlator/, before this script runs) clones current upstream
# main, cross-compiles it for aarch64, and overwrites
# winlator/app/src/main/assets/box64/box64-0.4.4.tzst in place -- keeping the
# exact same asset filename, since DefaultVersion.BOX64 = "0.4.4" is a
# hardcoded string referenced by exact match elsewhere in Winlator's own code,
# so no other Java needs to change for the app to pick up the new binary.
#
# GLIBC compatibility was checked directly rather than assumed: the real
# rootfs.tzst bundled in this same Winlator source (app/src/main/assets/
# rootfs.tzst) was extracted and its usr/lib/libc.so.6 inspected -- it is
# glibc 2.39, exactly matching what the Ubuntu 24.04 GitHub Actions runner's
# aarch64-linux-gnu cross-toolchain produces, so no older/more conservative
# build environment is needed.

print("0.24 patch applied: version bump only. The actual fix is the freshly "
      "cross-compiled Box64 binary swapped in by the CI workflow step, "
      "replacing the buggy bundled v0.4.4 (2026-08-03) build.")
