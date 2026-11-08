// nu-usage bridge — attributed per-app network usage via WinRT.
// Exports NuReadUsage(sentBuf, recvBuf, idBuf, idChars, capacity) -> count or <0.
// Item i: id = idBuf + i*idChars (UTF-16, NUL-terminated).
#include <windows.h>
#include <roapi.h>
#include <wrl.h>
#include <wrl/wrappers/corewrappers.h>
#include <windows.foundation.h>
#include <windows.networking.connectivity.h>

using namespace Microsoft::WRL;
using namespace Microsoft::WRL::Wrappers;
using namespace ABI::Windows::Foundation;
using namespace ABI::Windows::Foundation::Collections;
using namespace ABI::Windows::Networking::Connectivity;

typedef IAsyncOperation<IVectorView<AttributedNetworkUsage*>*> UsageOp;
typedef IAsyncOperationCompletedHandler<IVectorView<AttributedNetworkUsage*>*> UsageDone;

extern "C" __declspec(dllexport) int __stdcall NuReadUsage(
    long long startTicks, long long endTicks,
    UINT64* sentBuf, UINT64* recvBuf, wchar_t* idBuf, int idChars, int capacity)
{
    RoInitializeWrapper init(RO_INIT_MULTITHREADED);
    if (FAILED(init)) return (int)(HRESULT)init;

    ComPtr<INetworkInformationStatics> statics;
    HRESULT hr = RoGetActivationFactory(
        HStringReference(RuntimeClass_Windows_Networking_Connectivity_NetworkInformation).Get(),
        IID_PPV_ARGS(&statics));
    if (FAILED(hr)) return hr;

    ComPtr<IConnectionProfile> prof;
    hr = statics->GetInternetConnectionProfile(&prof);
    if (FAILED(hr) || !prof) return FAILED(hr) ? hr : E_FAIL;

    ComPtr<IConnectionProfile3> p3;
    hr = prof->QueryInterface(IID_PPV_ARGS(&p3));
    if (FAILED(hr)) return hr;

    DateTime s, e;
    s.UniversalTime = (UINT64)startTicks;
    e.UniversalTime = (UINT64)endTicks;
    NetworkUsageStates st{};

    ComPtr<UsageOp> op;
    hr = p3->GetAttributedNetworkUsageAsync(s, e, st, &op);
    if (FAILED(hr) || !op) return FAILED(hr) ? hr : E_FAIL;

    HANDLE evt = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    ComPtr<UsageDone> handler = Callback<UsageDone>(
        [evt](UsageOp* o, AsyncStatus) -> HRESULT { SetEvent(evt); return S_OK; });
    hr = op->put_Completed(handler.Get());
    if (FAILED(hr)) { CloseHandle(evt); return hr; }
    if (WaitForSingleObject(evt, 30000) != WAIT_OBJECT_0) { CloseHandle(evt); return HRESULT_FROM_WIN32(ERROR_TIMEOUT); }
    CloseHandle(evt);

    ComPtr<IVectorView<AttributedNetworkUsage*>> vec;
    hr = op->GetResults(&vec);
    if (FAILED(hr) || !vec) return FAILED(hr) ? hr : E_FAIL;

    unsigned n = 0;
    hr = vec->get_Size(&n);
    if (FAILED(hr)) return hr;

    int count = 0;
    for (unsigned i = 0; i < n && count < capacity; i++) {
        ComPtr<IAttributedNetworkUsage> u;
        if (FAILED(vec->GetAt(i, &u)) || !u) continue;
        UINT64 sent = 0, recv = 0;
        u->get_BytesSent(&sent);
        u->get_BytesReceived(&recv);
        HString id;
        u->get_AttributionId(id.GetAddressOf());
        UINT32 len = 0;
        PCWSTR s2 = id.GetRawBuffer(&len);
        wchar_t* dst = idBuf + (size_t)count * idChars;
        if (s2 && len) {
            if ((int)len >= idChars) len = idChars - 1;
            memcpy(dst, s2, len * sizeof(wchar_t));
            dst[len] = 0;
        } else {
            dst[0] = 0;
        }
        sentBuf[count] = sent;
        recvBuf[count] = recv;
        count++;
    }
    return count;
}

BOOL WINAPI DllMain(HINSTANCE, DWORD, LPVOID) { return TRUE; }
