#!/usr/bin/env bash
# B48-3: can the host build the transport plan library the JVM unit tests load (`buildHostTransportPlanLib`)?
#
# Usage: check-host-toolchain.sh <ndk-dir>
# Exit 0 and the clang's path on stdout when it can. Otherwise exit 1 and ONE line on stderr that says what is missing, so that the Gradle task fails with that line
# and not with a page of clang errors. The task builds a host `.so` with the NDK's own clang (C headers and the plan's three C++ files, no Android library), which needs:
# a Linux host (the NDK's host toolchain is looked up under toolchains/llvm/prebuilt/<host>), the NDK, its clang and its jni.h.
#
# HOST_OS_OVERRIDE is for the test of this script only.

ndk="${1:-}"
os="${HOST_OS_OVERRIDE:-$(uname -s)}"
fail() {
    echo "buildHostTransportPlanLib: $1. The JVM unit tests build the transport plan's host library with the NDK's own clang, so they need a Linux host with the NDK (29.0.14206865) installed; nothing else is built here." >&2
    exit 1
}

[ "$os" = "Linux" ] || fail "this host is $os and the NDK's host toolchain is only used here on Linux"
[ -n "$ndk" ] || fail "no NDK directory was given (the Android SDK has no NDK 29.0.14206865; install it with the SDK manager)"
[ -d "$ndk" ] || fail "the NDK directory $ndk does not exist"
prebuilt=""
for candidate in "$ndk"/toolchains/llvm/prebuilt/*; do
    [ -d "$candidate" ] && prebuilt="$candidate" && break
done
[ -n "$prebuilt" ] || fail "the NDK at $ndk has no host toolchain under toolchains/llvm/prebuilt"
[ -x "$prebuilt/bin/clang" ] || fail "the NDK's host clang $prebuilt/bin/clang is missing or not executable"
[ -f "$prebuilt/sysroot/usr/include/jni.h" ] || fail "the NDK's $prebuilt/sysroot/usr/include/jni.h is missing"
echo "$prebuilt/bin/clang"
