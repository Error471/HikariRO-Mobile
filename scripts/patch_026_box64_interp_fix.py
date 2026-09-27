from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2600")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.26.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.25", "0.26"), encoding="utf-8")

# 0.26 does not touch any Java env-var logic -- the real fix lives entirely in
# the CI workflow's Box64 cross-compile step, explained there.
#
# Root cause, found by directly reproducing the failure locally (not guessed):
#
# 0.25's on-device logs (hikari-startup-025.log / box64-trace-025.txt) showed
# real progress -- the 0x7ffe0000 KUSER_SHARED_DATA conflict from 0.24 is
# gone -- but startup now fails later with "wine: could not exec wineserver".
# Box64's own trace shows it tries 5 candidate paths for wineserver, and even
# the objectively *correct* one (.../opt/wine/bin/wineserver, confirmed to
# really exist in the bundled rootfs.tzst asset, and recognized by Box64 as a
# valid x86_64 ELF -- "IsX64=1") still fails with "posix_spawn returned 2"
# (ENOENT).
#
# Reading Box64's own source (src/wrapped/wrappedlibc.c, my_posix_spawn)
# shows *why*: when Wine's client asks Box64 to launch another x86_64
# program (wineserver), Box64 doesn't just run it -- it recursively
# re-executes *itself* (posix_spawn(box64path, [box64path, wineserver_path,
# ...])), so the new process can also translate that second binary. box64path
# is resolved once, at Box64's own startup, from its own argv[0]
# (core.c: `box64path = ResolveFile(argv[0], ...)`) -- i.e. it is simply
# Box64's own on-disk path, nothing fancier.
#
# Separately, HikariLauncherActivity's Java code (present since 0.15, "direct
# x86_64 Wine loader") already works around a real problem: our
# cross-compiled Box64 binary is a normal *dynamically linked* ELF whose
# recorded ELF interpreter (readelf -l box64 -> "Requesting program
# interpreter") is a generic build-time default
# (/lib/ld-linux-aarch64.so.1 with a stock aarch64-linux-gnu-gcc toolchain)
# that does not exist anywhere on a real Android device's actual filesystem
# root. A plain `execve()` of such a binary fails outright (ENOENT on the
# interpreter) -- so Java's launcher code searches the extracted rootfs for a
# real copy of ld-linux-aarch64.so.1 (found at .../usr/lib/ld-linux-aarch64.so.1)
# and, when found, invokes Box64 *through* that loader explicitly
# ("<loader> --library-path <libs> <box64> <guestExe>"), bypassing the
# kernel's own (impossible) interpreter lookup for that one, first-level
# launch.
#
# That workaround only covers the *first* launch, done from Java. Box64's own
# internal recursive self-relaunch (for wineserver, or any other guest x86_64
# program it needs to re-exec) does a plain posix_spawn(box64path, ...) with
# no such workaround -- it is a normal, direct exec of the Box64 binary, which
# hits the exact same "interpreter file does not exist" problem all over
# again, this time with no Java-side safety net. That is "posix_spawn
# returned 2".
#
# This was verified directly, end to end, in a local sandbox rather than
# guessed: a tiny C program mirroring Box64's own ResolveFile(argv[0]) +
# posix_spawn(self) logic was cross-compiled with a stock (unpatched)
# interpreter path and reproduced the *same class* of failure
# ("could not open '/lib/ld-linux-aarch64.so.1'") the instant it was
# recursively self-spawned outside of the "explicit loader" trick. Rebuilding
# that same test program with its ELF interpreter linked directly to the
# exact absolute on-device path
# (/data/user/0/com.hikariro.mobile/files/rootfs/usr/lib/ld-linux-aarch64.so.1,
# matching this app's real applicationId and RootFS.java's own "rootfs"
# folder name) made a plain, ordinary exec *and* the recursive self-spawn
# both succeed cleanly, with no loader indirection needed at all.
#
# The fix: bake that exact absolute path into Box64's own ELF interpreter at
# link time (-Wl,--dynamic-linker=...), in the CI workflow step, so every
# exec of Box64 -- the first one from Java, and every later recursive one
# Box64 does internally -- resolves correctly on a real device without
# needing any loader workaround. This mirrors what the *original* bundled
# Winlator box64-0.4.4.tzst asset already did for its own applicationId
# (readelf on it shows interpreter
# /data/data/com.winlator/files/rootfs/lib/ld-linux-aarch64.so.1) -- our
# builds simply never matched that convention for our own applicationId
# until now.

print("0.26 patch applied: version bump only. The actual fix bakes the correct "
      "on-device ELF interpreter path into the freshly cross-compiled Box64 "
      "binary (CI workflow step), so Box64's own internal recursive "
      "self-relaunch (needed to start wineserver) succeeds instead of failing "
      "with 'posix_spawn returned 2'.")
