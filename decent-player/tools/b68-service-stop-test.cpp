// B68 item 4 (audit H5): the service's stop gives the DAC back to Android.
//
// Host simulation with a fake usbfs, on the pattern of ReleasedDriversSignalBoundaryTest: what matters is what USBDEVFS_GETDRIVER reads on
// both audio interfaces after the stop path. The kernel is modelled from the sources the audit saved (B68-audit/sources):
//   - k510_devio.c releaseintf(): RELEASEINTERFACE drops usbfs from the interface and binds nothing;
//   - k510_devio.c proc_ioctl(USBDEVFS_CONNECT): device_attach() only when the interface has no driver, EBUSY otherwise;
//   - k510_card.c: snd-usb-audio matches the AudioControl interface only, and its probe takes every AudioStreaming interface that is free
//     ("skipping, already claimed" for one usbfs still holds); an AudioStreaming interface alone matches no driver;
//   - k510_devio.c usbdev_release(): closing the fd (a process that dies) releases what it claimed, with no CONNECT;
//   - AOSP UsbDeviceConnection.releaseInterface (conn14.cpp / usbhost14.c): RELEASEINTERFACE, then CONNECT on success.
//
// The stop path itself is Kotlin (DualPlayerEngine.release -> releaseDacOnStop -> UsbAudioDevice.releaseToAndroidNow -> handBackToAndroid),
// so its device calls are replayed here in the order that code makes them (pinned at source level by DacShareWiringBoundaryTest and
// ReleasedDriversSignalBoundaryTest); the two native steps are the real JNI functions of usb-audio-output.cpp.
#include <jni.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

extern "C" {
JNIEXPORT jint JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeReattachKernelDriverIfUnbound(JNIEnv *, jclass, jint, jint);
JNIEXPORT jstring JNICALL Java_com_decent_usbaudio_UsbAudioStream_nativeKernelDriverName(JNIEnv *, jclass, jint, jint);
}

namespace {

constexpr int kFd = 4242;
constexpr int kHid = 0, kControl = 1, kStreaming = 2;   // the Melody's layout (B68-audit §3: if0 HID, if1 AC, if2 AS)

struct Iface {
    bool audioControl = false;
    bool audioStreaming = false;
    std::string driver;          // "" = none
    bool claimedByFd = false;
};

struct FakeKernel {
    Iface ifaces[3];
    bool fdOpen = true;
    int connects = 0;

    void plugAndClaimDirect() {
        *this = FakeKernel();
        ifaces[kHid].driver = "usbhid";
        ifaces[kControl].audioControl = true;
        ifaces[kStreaming].audioStreaming = true;
        // Polylute's Direct claim: claimInterface(force = true) = DISCONNECT snd-usb-audio, then CLAIMINTERFACE (usbfs).
        for (int i : {kStreaming, kControl}) {
            ifaces[i].driver = "usbfs";
            ifaces[i].claimedByFd = true;
        }
    }

    // device_attach(): returns 1 when a driver bound, 0 when none matched.
    int attach(int ifno) {
        if (!ifaces[ifno].audioControl) return 0;   // snd-usb-audio's id table: AudioControl only
        ifaces[ifno].driver = "snd-usb-audio";
        for (auto &i : ifaces) {
            if (i.audioStreaming && i.driver.empty()) i.driver = "snd-usb-audio";   // usb_driver_claim_interface on the free AS
        }
        return 1;
    }

    // usbdev_release(): the fd closes (the process died, or close() after the hand-back).
    void closeFd() {
        for (auto &i : ifaces) {
            if (i.claimedByFd) {
                i.claimedByFd = false;
                i.driver.clear();   // releaseintf: usbfs goes, nothing binds, no CONNECT
            }
        }
        fdOpen = false;
    }
} g_kernel;

}  // namespace

extern "C" int ioctl(int fd, unsigned long request, ...) {
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    if (fd != kFd || !g_kernel.fdOpen) {
        errno = EBADF;
        return -1;
    }
    switch (request) {
        case USBDEVFS_CLAIMINTERFACE: {
            const unsigned int ifno = *static_cast<unsigned int *>(arg);
            Iface &i = g_kernel.ifaces[ifno];
            if (!i.driver.empty() && i.driver != "usbfs") { errno = EBUSY; return -1; }
            i.driver = "usbfs";
            i.claimedByFd = true;
            return 0;
        }
        case USBDEVFS_RELEASEINTERFACE: {
            const unsigned int ifno = *static_cast<unsigned int *>(arg);
            Iface &i = g_kernel.ifaces[ifno];
            if (!i.claimedByFd) { errno = EINVAL; return -1; }
            i.claimedByFd = false;
            i.driver.clear();
            return 0;
        }
        case USBDEVFS_GETDRIVER: {
            auto *gd = static_cast<usbdevfs_getdriver *>(arg);
            const Iface &i = g_kernel.ifaces[gd->interface];
            if (i.driver.empty()) { errno = ENODATA; return -1; }
            snprintf(gd->driver, sizeof(gd->driver), "%s", i.driver.c_str());
            return 0;
        }
        case USBDEVFS_IOCTL: {
            auto *ctl = static_cast<usbdevfs_ioctl *>(arg);
            Iface &i = g_kernel.ifaces[ctl->ifno];
            if (ctl->ioctl_code == (int)USBDEVFS_CONNECT) {
                g_kernel.connects++;
                if (!i.driver.empty()) { errno = EBUSY; return -1; }
                return g_kernel.attach(ctl->ifno);
            }
            if (ctl->ioctl_code == (int)USBDEVFS_DISCONNECT) {
                if (i.driver.empty()) { errno = ENODATA; return -1; }
                i.driver.clear();
                return 0;
            }
            errno = ENOTTY;
            return -1;
        }
        default:
            errno = ENOTTY;
            return -1;
    }
}

// liblog for the host (tools/host-stubs/android/log.h): the JNI functions log what they did; shown with SIM_LOG=1.
extern "C" int __android_log_print(int, const char *tag, const char *fmt, ...) {
    static const bool verbose = getenv("SIM_LOG") != nullptr;
    if (!verbose) return 0;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    return 0;
}

extern "C" void __android_log_assert(const char *cond, const char *tag, const char *fmt, ...) {
    fprintf(stderr, "ASSERT %s %s %s\n", tag, cond ? cond : "", fmt ? fmt : "");
    abort();
}

namespace {

int g_failures = 0;
#define CHECK(cond, ...)                         \
    do {                                         \
        if (!(cond)) {                           \
            printf("FAIL: " __VA_ARGS__);        \
            printf("\n");                        \
            g_failures++;                        \
        }                                        \
    } while (0)

std::string g_captured;
jstring captureString(JNIEnv *, const char *s) {
    g_captured = s;
    return reinterpret_cast<jstring>(&g_captured);
}

// GETDRIVER through the real JNI function, as the hand-back reads it (null -> "").
std::string driverName(int ifno) {
    JNINativeInterface table = {};
    table.NewStringUTF = captureString;
    JNIEnv env = {};
    env.functions = &table;
    g_captured.clear();
    jstring s = Java_com_decent_usbaudio_UsbAudioStream_nativeKernelDriverName(&env, nullptr, kFd, ifno);
    return s ? g_captured : std::string();
}

std::string driverAfterClose(int ifno) { return g_kernel.ifaces[ifno].driver; }

// android.hardware.UsbDeviceConnection.releaseInterface (AOSP JNI): RELEASEINTERFACE, then CONNECT when it succeeded.
bool aospReleaseInterface(unsigned int ifno) {
    int ret = ioctl(kFd, USBDEVFS_RELEASEINTERFACE, &ifno);
    if (ret == 0) {
        usbdevfs_ioctl ctl = {};
        ctl.ifno = (int)ifno;
        ctl.ioctl_code = USBDEVFS_CONNECT;
        ctl.data = nullptr;
        ioctl(kFd, USBDEVFS_IOCTL, &ctl);
    }
    return ret == 0;
}

// The device calls of UsbAudioDevice.handBackToAndroid (no level owed, no reset needed), in its order.
void handBack(int first, int second) {
    aospReleaseInterface((unsigned int)first);    // conn.releaseInterface(streaming)
    aospReleaseInterface((unsigned int)second);   // conn.releaseInterface(control)
    for (int ifno : {kControl, kStreaming}) {      // listOfNotNull(control, streaming).forEach { nativeReattachKernelDriverIfUnbound }
        Java_com_decent_usbaudio_UsbAudioStream_nativeReattachKernelDriverIfUnbound(nullptr, nullptr, kFd, ifno);
    }
}

void serviceStopReleasesInOrder() {
    g_kernel.plugAndClaimDirect();
    CHECK(driverName(kControl) == "usbfs" && driverName(kStreaming) == "usbfs", "Direct claim not modelled");
    handBack(kStreaming, kControl);
    const std::string control = driverName(kControl), streaming = driverName(kStreaming);
    CHECK(control == "snd-usb-audio", "control after the stop path: '%s'", control.c_str());
    CHECK(streaming == "snd-usb-audio", "streaming after the stop path: '%s'", streaming.c_str());
    g_kernel.closeFd();   // closeDevice(): nothing is claimed any more, the close changes nothing
    CHECK(driverAfterClose(kControl) == "snd-usb-audio" && driverAfterClose(kStreaming) == "snd-usb-audio",
          "closing the fd after the hand-back unbound something");
    CHECK(driverAfterClose(kHid) == "usbhid", "the HID interface was touched");
    printf("B68 service stop: releaseInterface(streaming) -> releaseInterface(control) -> reattach -> GETDRIVER control=%s streaming=%s; "
           "after close control=%s streaming=%s\n",
           control.c_str(), streaming.c_str(), driverAfterClose(kControl).c_str(), driverAfterClose(kStreaming).c_str());
}

void processKilledAfterTheStopPath() {
    // the stop path ran, then LMK / force-stop kills the process: nothing is claimed, so the kernel's close has nothing to undo
    g_kernel.plugAndClaimDirect();
    handBack(kStreaming, kControl);
    g_kernel.closeFd();
    CHECK(driverAfterClose(kControl) == "snd-usb-audio" && driverAfterClose(kStreaming) == "snd-usb-audio", "killed after the stop path lost the card");
    printf("B68 killed after the stop path: control=%s streaming=%s (the card stays)\n",
           driverAfterClose(kControl).c_str(), driverAfterClose(kStreaming).c_str());
}

void beforeB68StopHeldUsbfs() {
    // HEAD: onDestroy -> engine.release() released the players only ("device kept open"): no releaseInterface at all
    g_kernel.plugAndClaimDirect();
    const std::string control = driverName(kControl), streaming = driverName(kStreaming);
    CHECK(control == "usbfs" && streaming == "usbfs", "before B68 the stop path held usbfs");
    printf("B68 before (HEAD) while the process lives: control=%s streaming=%s (Android has no card)\n", control.c_str(), streaming.c_str());
}

void processKilledWithoutOnDestroy() {
    // force-stop / LMK with the DAC claimed and no onDestroy (or HEAD's onDestroy): the kernel's close releases usbfs without a CONNECT
    g_kernel.plugAndClaimDirect();
    const int connectsBefore = g_kernel.connects;
    g_kernel.closeFd();
    const std::string control = driverAfterClose(kControl), streaming = driverAfterClose(kStreaming);
    CHECK(control.empty() && streaming.empty(), "a killed process left a driver bound: control='%s' streaming='%s'", control.c_str(), streaming.c_str());
    CHECK(g_kernel.connects == connectsBefore, "the close sent a CONNECT");
    printf("B68 killed without onDestroy: control=%s streaming=%s (no driver until a replug)\n",
           control.empty() ? "(none)" : control.c_str(), streaming.empty() ? "(none)" : streaming.c_str());
}

void controlFirstLosesTheStream() {
    // why the order is streaming then control (AUD-003): control first creates the card while usbfs still holds the AS interface
    g_kernel.plugAndClaimDirect();
    handBack(kControl, kStreaming);
    const std::string control = driverName(kControl), streaming = driverName(kStreaming);
    CHECK(control == "snd-usb-audio", "control-first: control '%s'", control.c_str());
    CHECK(streaming.empty(), "control-first unexpectedly bound the stream: '%s'", streaming.c_str());
    printf("B68 control-first (wrong order, the AUD-003 dead route): control=%s streaming=%s\n",
           control.c_str(), streaming.empty() ? "(none)" : streaming.c_str());
}

}  // namespace

int main() {
    serviceStopReleasesInOrder();
    processKilledAfterTheStopPath();
    beforeB68StopHeldUsbfs();
    processKilledWithoutOnDestroy();
    controlFirstLosesTheStream();
    printf("B68 service stop simulation %s failures=%d\n", g_failures ? "FAIL" : "PASS", g_failures);
    return g_failures ? 1 : 0;
}
