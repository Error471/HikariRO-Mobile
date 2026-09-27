from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2500")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.25.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.24", "0.25"), encoding="utf-8")

# 0.25 does not change any Java/env-var logic either -- same as 0.24, the real
# fix lives in the CI workflow, not here.
#
# What actually changed: the 0.24 build proved (via real on-device logs,
# hikari-startup-024.log / box64-trace-024.txt, both analyzed directly) that
# swapping in a newer *stock* upstream Box64 build was not enough -- the crash
# was byte-for-byte identical to 0.23's. Box64 v0.4.5 (built from commit
# ac9b13a, 2026-09-26) still hits dlsym(NULL, "wine_main_preload_info") ->
# NULL, because Box64 has never published that symbol in any version -- it
# substitutes its own hardcoded 3-range "WINE prereserve"
# (src/tools/wine_tools.c) for Wine's real preloader instead of actually
# exec'ing one, so there is no real preloader binary to ever define/export
# that symbol via the normal mechanism. This is not a regression that got
# fixed upstream; it's simply how Box64's Wine-WOW64 path always works.
#
# The actual, more surgical fix: Box64's 3rd hardcoded prereserve range is
# [0x7f000000, 0x82000000) -- and that range squarely covers 0x7ffe0000, the
# fixed address of Windows' KUSER_SHARED_DATA page, which is exactly the
# address ntdll's fallback NtAllocateVirtualMemory tries (and fails on,
# c0000018 STATUS_CONFLICTING_ADDRESSES) once it can't find preload info.
# wine_tools.c's own comment says this reserve is only Box64's own
# approximation of what wine-preloader normally reserves ("only the
# prereserve argument is reserved, not the other zone that wine-preloader
# reserve") -- so shrinking it to end right at 0x7ffe0000 instead of
# 0x82000000 frees exactly the one page ntdll needs, without touching the
# other two prereserve ranges Box64 also sets up.
#
# The "Cross-compile patched Box64 from source" workflow step (still right
# after checking out winlator/, before this script runs) now applies that
# one-line sed patch to wine_tools.c before compiling, and fails the build
# outright if the patch doesn't apply cleanly (upstream line changed) rather
# than silently shipping an unpatched binary.

print("0.25 patch applied: version bump only. The actual fix is the shrunk "
      "Box64 WINE-prereserve range (ends at 0x7ffe0000 instead of "
      "0x82000000), applied to the freshly cross-compiled Box64 by the CI "
      "workflow step, to stop it from reserving the exact page Wine's ntdll "
      "needs for KUSER_SHARED_DATA.")
