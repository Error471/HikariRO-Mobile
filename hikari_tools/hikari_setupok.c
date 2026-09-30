// hikari_setupok.exe: acepta solo el dialogo "Ragnarok Setup" (Setup.exe) que
// raghikari.exe abre la primera vez, sin cambiar nada (equivale a pulsar Aceptar).
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <string.h>

#define TIMEOUT_MS 60000
#define POLL_MS 150

static DWORD setupPids[16];
static int setupCount = 0;
static int found = 0;

static void refresh_pids(void) {
    PROCESSENTRY32 pe;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    setupCount = 0;
    if (snap == INVALID_HANDLE_VALUE) return;
    pe.dwSize = sizeof(pe);
    if (Process32First(snap, &pe)) {
        do {
            if (_stricmp(pe.szExeFile, "setup.exe") == 0 && setupCount < 16) setupPids[setupCount++] = pe.th32ProcessID;
        } while (Process32Next(snap, &pe));
    }
    CloseHandle(snap);
}

static BOOL CALLBACK on_window(HWND hwnd, LPARAM lp) {
    DWORD pid = 0;
    int i;
    char cls[32] = "";
    GetWindowThreadProcessId(hwnd, &pid);
    for (i = 0; i < setupCount; i++) if (setupPids[i] == pid) break;
    if (i == setupCount || !IsWindowVisible(hwnd)) return TRUE;
    GetClassNameA(hwnd, cls, sizeof(cls));
    if (strcmp(cls, "#32770") != 0) return TRUE;
    found = 1;
    // Fuera de la vista y Aceptar (IDOK)
    SetWindowPos(hwnd, NULL, -4000, -4000, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    HWND ok = GetDlgItem(hwnd, IDOK);
    if (ok) PostMessageA(hwnd, WM_COMMAND, MAKEWPARAM(IDOK, BN_CLICKED), (LPARAM)ok);
    else PostMessageA(hwnd, WM_COMMAND, MAKEWPARAM(IDOK, BN_CLICKED), 0);
    (void)lp;
    return TRUE;
}

int WINAPI WinMain(HINSTANCE inst, HINSTANCE prev, LPSTR cmd, int show) {
    HANDLE mutex = CreateMutexA(NULL, TRUE, "HikariSetupOk");
    DWORD start = GetTickCount(), lastSeen = 0;
    if (GetLastError() == ERROR_ALREADY_EXISTS) return 0;
    while (GetTickCount() - start < TIMEOUT_MS) {
        refresh_pids();
        found = 0;
        if (setupCount > 0) EnumWindows(on_window, 0);
        if (found) lastSeen = GetTickCount();
        else if (lastSeen && setupCount == 0) break;
        Sleep(found ? 1000 : POLL_MS);
    }
    if (mutex) CloseHandle(mutex);
    (void)inst; (void)prev; (void)cmd; (void)show;
    return 0;
}
