from pathlib import Path
import re

root = Path("winlator/app")

# Version metadata.
build = root / "app/build.gradle"
s = build.read_text(encoding="utf-8")
s = re.sub(r'versionCode\s+[^\n]+', 'versionCode Integer.parseInt("2300")', s, count=1)
s = re.sub(r'versionName\s+[^\n]+', 'versionName String.valueOf("0.23.0-beta")', s, count=1)
build.write_text(s, encoding="utf-8")

for rel in [
    "app/src/main/java/com/winlator/HikariDiagnostics.java",
    "app/src/main/java/com/winlator/HikariStartupDialog.java",
    "app/src/main/java/com/winlator/HikariLauncherActivity.java",
]:
    p = root / rel
    if p.exists():
        p.write_text(p.read_text(encoding="utf-8").replace("0.22", "0.23"), encoding="utf-8")

# Real progress made in 0.20-0.22, verified against the actual real device logs
# (hikari-startup-020.log / hikari-trace-020.log / box64-trace-020.txt) plus
# direct reading of Wine's own source (dlls/ntdll/unix/loader.c, loader/main.c)
# and Box64's public documentation/issue tracker (ptitSeb/box64):
#
# * raghikari.exe (and the stock Ragexe.exe) are confirmed 32-bit (PE i386)
#   executables -- verified directly against the real client folder.
# * 0.20's WINELOADERNOEXEC=1 got further than any other attempt: it skips
#   Wine's self-reexec entirely (which is what was crashing with "could not
#   exec the wine loader" in 0.18/0.19/0.21), and box64's own trace log shows
#   it then goes on to actually dlopen the real ntdll.so and resolve
#   __wine_main successfully -- real progress, not a guess.
# * The next thing that log shows is Box64 warning "Didn't detect 48bits of
#   address space, considering it's 39bits" right before it does its own
#   hardcoded address-space "WINE prereserve" (mimicking what Wine's own
#   preloader binary would normally do, since Box64 never actually execs a
#   separate preloader stub, it dlopens ntdll.so directly instead). This is a
#   documented, known box64 quirk on some ARM64 Android chipsets (see
#   ptitSeb/box64 issues #1365 and #2239), and the failure this specific game
#   hit right after ("failed to map the shared user data: c0000018") is
#   exactly the kind of address-space-layout mismatch that mismatch causes.
# * Box64's own manual (docs/box64.pod) documents two environment variables
#   built specifically for this exact scenario -- a 32-bit Windows exe running
#   through Wine's WOW64 layer inside a 64-bit wine process under Box64:
#     BOX64_MMAP32: "Force 32-bit compatible memory mappings on 64-bit
#     programs that run 32-bit code (like Wine WOW64)"
#     BOX64_RESERVE_HIGH: "Reserve high memory area for the program"
#   Neither of these was set in any previous HikariRO Mobile build.
# * 0.21's WINEARCH=wow64 alone did not help (confirmed on-device: identical
#   "could not exec the wine loader" failure came back) -- but that was
#   without WINELOADERNOEXEC=1, so Wine's self-reexec was still being
#   attempted and still failing before WINEARCH ever got a chance to matter.
#   WINEARCH=wow64 does more than just change the reexec decision: it also
#   tells Wine to actually use its unified 64-bit-process WOW64 CPU emulation
#   for the 32-bit game, which is the mechanism that will actually let a
#   32-bit raghikari.exe run at all once startup gets past this point -- so
#   it is combined with WINELOADERNOEXEC=1 here rather than tried alone again.
#
# This patch combines all of the above into one attempt: skip the broken
# self-reexec (WINELOADERNOEXEC=1), tell Wine to use its real WOW64 path for
# the 32-bit game (WINEARCH=wow64), and tell Box64 to lay out memory the way
# its own documentation says a Wine-WOW64 scenario needs (BOX64_MMAP32=1,
# BOX64_RESERVE_HIGH=1). The 0.22 WINELOADER-path override is dropped: it is
# fully superseded by WINELOADERNOEXEC=1, which skips the codepath that would
# have read it in the first place.
guest = root / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
t = guest.read_text(encoding="utf-8")

old_loader_block = (
    '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);\n'
    '        envVars.put("WINELOADER", rootDir+rootFS.getWinePath()+"/bin/wine");\n'
    '        HikariDiagnostics.record(environment.getContext(), "WINELOADER=" + rootDir+rootFS.getWinePath()+"/bin/wine");'
)
new_loader_block = (
    '        envVars.put("ANDROID_SYSVSHM_SERVER", rootDir+UnixSocketConfig.SYSVSHM_SERVER_PATH);\n'
    '        envVars.put("WINELOADERNOEXEC", "1");\n'
    '        envVars.put("WINEARCH", "wow64");\n'
    '        HikariDiagnostics.record(environment.getContext(), "WINELOADERNOEXEC=1 WINEARCH=wow64 BOX64_MMAP32=1 BOX64_RESERVE_HIGH=1");'
)
if old_loader_block not in t:
    raise RuntimeError("No se encontro el bloque de envVars de la 0.22 (WINELOADER) en execGuestProgram")
t = t.replace(old_loader_block, new_loader_block, 1)

old_box64_block = (
    '        envVars.put("BOX64_DYNAREC", "1");\n'
    '        envVars.put("BOX64_UNITYPLAYER", "0");\n'
    '        envVars.put("BOX64_DYNACACHE", "0");'
)
new_box64_block = (
    '        envVars.put("BOX64_DYNAREC", "1");\n'
    '        envVars.put("BOX64_UNITYPLAYER", "0");\n'
    '        envVars.put("BOX64_DYNACACHE", "0");\n'
    '        envVars.put("BOX64_MMAP32", "1");\n'
    '        envVars.put("BOX64_RESERVE_HIGH", "1");'
)
if old_box64_block not in t:
    raise RuntimeError("No se encontro el bloque de BOX64_DYNAREC en addBox64EnvVars")
t = t.replace(old_box64_block, new_box64_block, 1)

guest.write_text(t, encoding="utf-8")

# Widen the on-device WINEDEBUG channels so that if this still fails, the next
# log actually shows what ntdll's virtual-memory subsystem (mmap_init /
# virtual_alloc_first_teb) tried to do and why, instead of just where it gave
# up -- "+virtual" is Wine's own debug channel for exactly that subsystem.
launcher = root / "app/src/main/java/com/winlator/HikariLauncherActivity.java"
lt = launcher.read_text(encoding="utf-8")
old_channels = '.putString("wine_debug_channels", "seh,loaddll")'
new_channels = '.putString("wine_debug_channels", "seh,loaddll,virtual")'
if old_channels not in lt:
    raise RuntimeError("No se encontro wine_debug_channels en HikariLauncherActivity")
lt = lt.replace(old_channels, new_channels, 1)
launcher.write_text(lt, encoding="utf-8")

print("0.23 patch applied: WINELOADERNOEXEC=1 + WINEARCH=wow64 + BOX64_MMAP32=1 + BOX64_RESERVE_HIGH=1, plus +virtual WINEDEBUG for better diagnostics if it still fails")
