/* HikariRO Movil: sustituto de Internet Explorer dentro de Wine.
   Wine abre los enlaces "en ventana nueva" pidiendo a COM un Internet Explorer
   fuera de proceso (CLSID_InternetExplorer, LocalServer32). Este programa se
   registra en su lugar, recibe la URL y la pasa a Android (winhandler.exe /url),
   que la abre en el navegador del movil. */
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <ole2.h>
#include <exdisp.h>
#include <urlmon.h>
#include <shellapi.h>
#include <wchar.h>
#include <stdio.h>
#include <stdarg.h>

static const CLSID CLSID_IE = {0x0002DF01,0x0000,0x0000,{0xC0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const IID IID_WB2    = {0xD30C1661,0xCDAF,0x11D0,{0x8A,0x3E,0x00,0xC0,0x4F,0xC9,0xE2,0x6E}};
static const IID IID_WBApp  = {0x0002DF05,0x0000,0x0000,{0xC0,0x00,0x00,0x00,0x00,0x00,0x00,0x46}};
static const IID IID_WB     = {0xEAB22AC1,0x30C1,0x11CF,{0xA7,0xEB,0x00,0x00,0xC0,0x5B,0xAE,0x0B}};
static const IID IID_TFP    = {0x9216E421,0x2BF5,0x11D0,{0x82,0xB4,0x00,0xA0,0xC9,0x0C,0x29,0xC5}};
static const IID IID_TFP2   = {0xB2C867E6,0x69D6,0x46F2,{0xA6,0x11,0xDE,0xD9,0xA4,0xBD,0x7F,0xEF}};

static DWORD g_main_thread;

/* ---- registro: C:\windows\temp\hikari_openurl.log ---- */
static void hlog(const char *fmt, ...)
{
    char buf[1024]; va_list ap; SYSTEMTIME t; DWORD w; HANDLE f;
    GetLocalTime(&t);
    int n = _snprintf(buf, sizeof(buf), "%02d:%02d:%02d.%03d [%lu] ", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds, GetCurrentProcessId());
    va_start(ap, fmt); n += _vsnprintf(buf + n, sizeof(buf) - n - 2, fmt, ap); va_end(ap);
    if (n < 0 || n > (int)sizeof(buf) - 2) n = sizeof(buf) - 2;
    buf[n++] = '\r'; buf[n++] = '\n';
    f = CreateFileA("C:\\windows\\temp\\hikari_openurl.log", FILE_APPEND_DATA, FILE_SHARE_READ|FILE_SHARE_WRITE, NULL, OPEN_ALWAYS, 0, NULL);
    if (f != INVALID_HANDLE_VALUE) { WriteFile(f, buf, n, &w, NULL); CloseHandle(f); }
}
static LONG g_opened = 0;

/* ---- abrir URL en Android ---- */
static void open_url(const WCHAR *url)
{
    if (!url || !*url) return;
    if (wcsncmp(url, L"about:", 6) == 0 || wcsncmp(url, L"javascript:", 11) == 0) return;
    InterlockedExchange(&g_opened, 1);
    hlog("open_url: %ls", url);
    /* http/https -> winebrowser.exe -> (registro WineBrowser) winhandler.exe /url -> Android */
    HINSTANCE r = ShellExecuteW(NULL, L"open", url, NULL, NULL, SW_SHOWNORMAL);
    hlog("ShellExecute -> %p", (void*)r);
}

static void request_exit(void) { PostThreadMessageW(g_main_thread, WM_QUIT, 0, 0); }

/* ---- objeto: IWebBrowser2 + ITargetFramePriv2 ---- */
typedef struct {
    const void *lpVtblWB;   /* IWebBrowser2 */
    const void *lpVtblTF;   /* ITargetFramePriv2 */
    LONG ref;
} Browser;
static Browser g_browser;

#define FROM_TF(p) ((Browser*)((char*)(p) - offsetof(Browser, lpVtblTF)))

static HRESULT STDMETHODCALLTYPE b_QI(Browser *b, REFIID riid, void **ppv)
{
    hlog("QI {%08lX-%04X-%04X}", riid->Data1, riid->Data2, riid->Data3);
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IDispatch) || IsEqualIID(riid, &IID_WB) ||
        IsEqualIID(riid, &IID_WBApp) || IsEqualIID(riid, &IID_WB2)) *ppv = &b->lpVtblWB;
    else if (IsEqualIID(riid, &IID_TFP) || IsEqualIID(riid, &IID_TFP2)) *ppv = &b->lpVtblTF;
    else { *ppv = NULL; return E_NOINTERFACE; }
    InterlockedIncrement(&b->ref);
    return S_OK;
}

/* IWebBrowser2 */
static HRESULT STDMETHODCALLTYPE wb_QI(void *This, REFIID riid, void **ppv) { return b_QI((Browser*)This, riid, ppv); }
static ULONG STDMETHODCALLTYPE wb_AddRef(void *This) { return InterlockedIncrement(&((Browser*)This)->ref); }
static ULONG STDMETHODCALLTYPE wb_Release(void *This) { return InterlockedDecrement(&((Browser*)This)->ref); }
static HRESULT STDMETHODCALLTYPE wb_GetTypeInfoCount(void *This, UINT *p) { if (p) *p = 0; return S_OK; }
static HRESULT STDMETHODCALLTYPE wb_notimpl(void) { return E_NOTIMPL; }   /* x64: el llamador limpia la pila */
static HRESULT STDMETHODCALLTYPE wb_Navigate(void *This, BSTR url, VARIANT *a, VARIANT *b, VARIANT *c, VARIANT *d) { open_url(url); return S_OK; }
static HRESULT STDMETHODCALLTYPE wb_Quit(void *This) { hlog("Quit"); request_exit(); return S_OK; }
static HRESULT STDMETHODCALLTYPE wb_putBool(void *This, VARIANT_BOOL v) { hlog("put_Visible"); return S_OK; }
static HRESULT STDMETHODCALLTYPE wb_Navigate2(void *This, VARIANT *url, VARIANT *a, VARIANT *b, VARIANT *c, VARIANT *d)
{
    if (url && V_VT(url) == VT_BSTR) open_url(V_BSTR(url));
    else if (url && V_VT(url) == (VT_BSTR|VT_BYREF) && V_BSTRREF(url)) open_url(*V_BSTRREF(url));
    return S_OK;
}

/* ITargetFramePriv2 */
static HRESULT STDMETHODCALLTYPE tf_QI(void *This, REFIID riid, void **ppv) { return b_QI(FROM_TF(This), riid, ppv); }
static ULONG STDMETHODCALLTYPE tf_AddRef(void *This) { return InterlockedIncrement(&FROM_TF(This)->ref); }
static ULONG STDMETHODCALLTYPE tf_Release(void *This) { return InterlockedDecrement(&FROM_TF(This)->ref); }
static HRESULT STDMETHODCALLTYPE tf_NavigateHack(void *This, DWORD f, IBindCtx *pbc, IBindStatusCallback *cb, LPCWSTR target, LPCWSTR url, LPCWSTR loc) { open_url(url); return S_OK; }
static HRESULT STDMETHODCALLTYPE tf_AggregatedNavigation2(void *This, DWORD f, IBindCtx *pbc, IBindStatusCallback *cb, LPCWSTR target, IUri *uri, LPCWSTR loc)
{
    BSTR url = NULL;
    hlog("AggregatedNavigation2 uri=%p", uri);
    if (uri && SUCCEEDED(IUri_GetDisplayUri(uri, &url)) && url) { open_url(url); SysFreeString(url); }
    return S_OK;
}

/* Tabla de IWebBrowser2: 7 (IDispatch) + 25 (IWebBrowser) + 20 (IWebBrowserApp) + 20 (IWebBrowser2) = 72 */
#define WB_VTBL_SIZE 72
static void *wb_vtbl[WB_VTBL_SIZE];
static void *tf_vtbl[10];

static void init_vtables(void)
{
    int i;
    for (i = 0; i < WB_VTBL_SIZE; i++) wb_vtbl[i] = (void*)wb_notimpl;
    wb_vtbl[0] = (void*)wb_QI; wb_vtbl[1] = (void*)wb_AddRef; wb_vtbl[2] = (void*)wb_Release;
    wb_vtbl[3] = (void*)wb_GetTypeInfoCount;
    /* IWebBrowser: GoBack7 GoForward8 GoHome9 GoSearch10 Navigate11 Refresh12 Refresh2 13 Stop14 ... */
    wb_vtbl[11] = (void*)wb_Navigate;
    /* IWebBrowserApp empieza en 32: Quit32 ... put_Visible 41 ... */
    wb_vtbl[32] = (void*)wb_Quit;
    wb_vtbl[41] = (void*)wb_putBool;       /* put_Visible */
    /* IWebBrowser2 empieza en 52: Navigate2 52 */
    wb_vtbl[52] = (void*)wb_Navigate2;
    for (i = 0; i < 10; i++) tf_vtbl[i] = (void*)wb_notimpl;
    tf_vtbl[0] = (void*)tf_QI; tf_vtbl[1] = (void*)tf_AddRef; tf_vtbl[2] = (void*)tf_Release;
    tf_vtbl[7] = (void*)tf_NavigateHack;   /* ITargetFramePriv: 3 FindFrameDownwards 4 FindFrameInContext 5 OnChildActivate 6 OnChildDeactivate 7 NavigateHack 8 FindBrowserByIndex */
    tf_vtbl[9] = (void*)tf_AggregatedNavigation2;
    g_browser.lpVtblWB = wb_vtbl; g_browser.lpVtblTF = tf_vtbl; g_browser.ref = 1;
}

/* ---- fabrica de clase ---- */
static HRESULT STDMETHODCALLTYPE cf_QI(IClassFactory *This, REFIID riid, void **ppv)
{
    if (IsEqualIID(riid, &IID_IUnknown) || IsEqualIID(riid, &IID_IClassFactory)) { *ppv = This; return S_OK; }
    *ppv = NULL; return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE cf_AddRef(IClassFactory *This) { return 2; }
static ULONG STDMETHODCALLTYPE cf_Release(IClassFactory *This) { return 1; }
static HRESULT STDMETHODCALLTYPE cf_CreateInstance(IClassFactory *This, IUnknown *outer, REFIID riid, void **ppv)
{
    hlog("CreateInstance");
    if (outer) return CLASS_E_NOAGGREGATION;
    return b_QI(&g_browser, riid, ppv);
}
static HRESULT STDMETHODCALLTYPE cf_LockServer(IClassFactory *This, BOOL lock) { return S_OK; }
static IClassFactoryVtbl cf_vtbl = { cf_QI, cf_AddRef, cf_Release, cf_CreateInstance, cf_LockServer };
static IClassFactory g_factory = { &cf_vtbl };

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, LPWSTR cmdline, int show)
{
    MSG msg; DWORD cookie = 0; int argc = 0, i;
    LPWSTR *argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    hlog("inicio: %ls", GetCommandLineW());
    BOOL embedding = FALSE;
    for (i = 1; i < argc; i++) {
        if (_wcsicmp(argv[i], L"-embedding") == 0 || _wcsicmp(argv[i], L"/embedding") == 0) embedding = TRUE;
        else if (wcsstr(argv[i], L"://")) open_url(argv[i]);   /* iexplore.exe http://... */
    }
    if (!embedding) return 0;

    g_main_thread = GetCurrentThreadId();
    init_vtables();
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    HRESULT hr = CoRegisterClassObject(&CLSID_IE, (IUnknown*)&g_factory, CLSCTX_LOCAL_SERVER, REGCLS_MULTIPLEUSE, &cookie);
    hlog("CoRegisterClassObject -> %08lx", hr);
    if (FAILED(hr)) return 1;
    SetTimer(NULL, 0, 20000, NULL);  /* no quedarse vivo si nadie llama */
    while (GetMessageW(&msg, NULL, 0, 0) > 0) {
        if (msg.message == WM_TIMER) break;
        TranslateMessage(&msg); DispatchMessageW(&msg);
    }
    /* dar tiempo a que termine la llamada en curso */
    if (g_opened) Sleep(500);
    hlog("saliendo");
    CoRevokeClassObject(cookie);
    CoUninitialize();
    return 0;
}
