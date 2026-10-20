#!/usr/bin/env bash
set -euo pipefail

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compiler="${CXX:-}"
if [[ -z "$compiler" ]]; then
  compiler="$(find "$HOME/Android/Sdk/ndk" -path '*/toolchains/llvm/prebuilt/linux-x86_64/bin/clang' -type l 2>/dev/null | sort -V | tail -n 1)"
fi
[[ -x "$compiler" ]] || { echo "Host C++ compiler unavailable" >&2; exit 2; }

output="$(mktemp -t direct-sequence-boundaries.XXXXXX)"
trap 'rm -f "$output"' EXIT

"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/direct-sequence-boundaries-test.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/usb-pcm-packetizer.cpp" \
  -o "$output"
"$output"

# AUD-008 / AUD-055: PCM conversions shared by the native engine and the raw/float write paths.
convert_output="$(mktemp -t usb-pcm-convert.XXXXXX)"
trap 'rm -f "$output" "$convert_output"' EXIT
"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/usb-pcm-convert-test.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/usb-pcm-convert.cpp" \
  -o "$convert_output"
"$convert_output"

# AUD-112 follow-up: bounded barrier drain — pending set and quarantine of URBs not yet returned.
quarantine_output="$(mktemp -t urb-quarantine.XXXXXX)"
trap 'rm -f "$output" "$convert_output" "$quarantine_output"' EXIT
"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/urb-quarantine-test.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/urb-quarantine.cpp" \
  -o "$quarantine_output"
"$quarantine_output"

# AUD-107: click-free native seek — ramp shapes, silence feed with no empty microframe, continuity.
seek_output="$(mktemp -t seek-barrier.XXXXXX)"
trap 'rm -f "$output" "$convert_output" "$quarantine_output" "$seek_output"' EXIT
"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/seek-barrier-test.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/seek-barrier.cpp" \
  -o "$seek_output" -lm
"$seek_output"

# AUD-014: the decode thread is joined exactly once, also when it ended on its own.
thread_output="$(mktemp -t joinable-thread.XXXXXX)"
trap 'rm -f "$output" "$convert_output" "$quarantine_output" "$seek_output" "$thread_output"' EXIT
"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/joinable-thread-test.cpp" \
  -o "$thread_output" -lpthread
"$thread_output"

# AUD-124: USB_GLITCH rate limiting and the feedback reaction after a stream start.
glitch_output="$(mktemp -t usb-glitch.XXXXXX)"
trap 'rm -f "$output" "$convert_output" "$quarantine_output" "$seek_output" "$thread_output" "$glitch_output"' EXIT
"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/usb-glitch-test.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/usb-glitch.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/usb-pcm-packetizer.cpp" \
  -o "$glitch_output" -lm
"$glitch_output"

# B27-A5 (N-07): the one-second summaries of the stream: the last 30 s and nothing more, min / max / mean, the dump's fields.
second_output="$(mktemp -t usb-second-ring.XXXXXX)"
trap 'rm -f "$output" "$convert_output" "$quarantine_output" "$seek_output" "$thread_output" "$glitch_output" "$second_output"' EXIT
"$compiler" -std=c++11 -Wall -Wextra -Werror \
  -I"$repo/libs/decent-usb-audio-driver/src/main/jni" \
  "$repo/tools/usb-second-ring-test.cpp" \
  "$repo/libs/decent-usb-audio-driver/src/main/jni/usb-second-ring.cpp" \
  -o "$second_output" -lpthread
"$second_output"

# AUD-144 / AUD-143 / AUD-145: the real native engine and USB output against a fake usbfs.
# natural-join-test.cpp runs usb-audio-output.cpp, native-audio-engine.cpp, flac_parser.cc and libFLAC
# through the JNI entry points the sink uses; only `ioctl` and `poll` on one fd are faked. Needs the NDK
# toolchain's own host libc++ (the host has no C++ standard library headers) and builds libFLAC from the
# repo's sources, so it is slower than the rest (~20 s).
ndk_prebuilt="$(cd "$(dirname "$compiler")/.." && pwd)"
libcxx_include="$ndk_prebuilt/sysroot/usr/include/c++/v1"
libcxx_lib="$ndk_prebuilt/lib/libc++.a"
libcxxabi_lib="$ndk_prebuilt/lib/libc++abi.a"
if [[ ! -f "$libcxx_include/__config_site" || ! -f "$libcxx_lib" || ! -f "$libcxxabi_lib" || ! -f "$ndk_prebuilt/sysroot/usr/include/jni.h" ]]; then
  echo "natural join simulation needs the NDK toolchain's host libc++ and jni.h under $ndk_prebuilt" >&2
  exit 2
fi
sim_dir="$(mktemp -d -t natural-join.XXXXXX)"
trap 'rm -rf "$sim_dir"; rm -f "$output" "$convert_output" "$quarantine_output" "$seek_output" "$thread_output" "$glitch_output" "$second_output"' EXIT
mkdir -p "$sim_dir/stubs" "$sim_dir/cxx" "$sim_dir/flac"
cp -r "$repo/tools/host-stubs/android" "$sim_dir/stubs/android"
ln -s "$ndk_prebuilt/sysroot/usr/include/jni.h" "$sim_dir/stubs/jni.h"
# The NDK's libc++ headers name the Android ABI namespace; the host libc++.a is the plain one.
sed -e 's/__ndk1/__1/' -e 's/_LIBCPP_HAS_THREAD_API_PTHREAD 0/_LIBCPP_HAS_THREAD_API_PTHREAD 1/' \
  "$libcxx_include/__config_site" > "$sim_dir/cxx/__config_site"

jni_dir="$repo/libs/decent-usb-audio-driver/src/main/jni"
flac_dir="$repo/libs/decent-media3-decoder-flac/src/main/jni"
for f in bitmath bitreader bitwriter cpu crc fixed float format lpc md5 memory metadata_iterators \
         metadata_object stream_decoder stream_encoder stream_encoder_framing window; do
  "$compiler" -c -O2 -std=gnu11 -w -DHAVE_STDINT_H=1 -DHAVE_STDBOOL_H=1 -DHAVE_INTTYPES_H=1 -DHAVE_LROUND=1 \
    -DFLAC__HAS_OGG=0 -DFLAC__NO_DLL -DPACKAGE_VERSION='"1.5.0"' \
    -I"$flac_dir/libflac/src/libFLAC/include" -I"$flac_dir/libflac/include" \
    "$flac_dir/libflac/src/libFLAC/$f.c" -o "$sim_dir/flac/$f.o"
done
sim_includes=(-I"$sim_dir/stubs" -I"$jni_dir" -I"$flac_dir/include" -I"$flac_dir/libflac/include")
sim_cxx=(-std=c++11 -nostdinc++ -isystem "$sim_dir/cxx" -isystem "$libcxx_include" -O1 -D_GNU_SOURCE)
join_output="$sim_dir/natural-join-test"
"$compiler" "${sim_cxx[@]}" -Wall -Wextra -Werror -Wno-unused-parameter "${sim_includes[@]}" \
  "$repo/tools/natural-join-test.cpp" \
  -c -o "$sim_dir/natural-join-test.o"
"$compiler" "${sim_cxx[@]}" -w "${sim_includes[@]}" \
  "$sim_dir/natural-join-test.o" \
  "$jni_dir/usb-audio-output.cpp" "$jni_dir/usb-pcm-packetizer.cpp" "$jni_dir/usb-pcm-convert.cpp" \
  "$jni_dir/urb-quarantine.cpp" "$jni_dir/seek-barrier.cpp" "$jni_dir/usb-glitch.cpp" "$jni_dir/usb-second-ring.cpp" \
  "$jni_dir/usb-payload-validation.cpp" "$jni_dir/native-audio-engine.cpp" "$flac_dir/flac_parser.cc" \
  "$sim_dir"/flac/*.o \
  -o "$join_output" -nostdlib++ "$libcxx_lib" "$libcxxabi_lib" -lpthread -lm
"$join_output"

# B14-1 / audit A-03 (T-11): the one-shot feedback read at stream start against a strict fake usbfs with fault
# injection (late completion after a cancel, foreign completions, device gone, full quarantine). `free` is wrapped
# so freeing a URB the kernel still holds is a failure. B27-A2 (R-02) runs the same faults against the CONTINUOUS
# feedback URB and the ring's fallback. No libFLAC: only the output and its helpers are linked.
feedback_output="$sim_dir/feedback-lifetime-test"
"$compiler" "${sim_cxx[@]}" -Wall -Wextra -Werror -Wno-unused-parameter "${sim_includes[@]}" \
  "$repo/tools/feedback-lifetime-test.cpp" \
  -c -o "$sim_dir/feedback-lifetime-test.o"
"$compiler" "${sim_cxx[@]}" -w "${sim_includes[@]}" \
  "$sim_dir/feedback-lifetime-test.o" \
  "$jni_dir/usb-audio-output.cpp" "$jni_dir/usb-pcm-packetizer.cpp" "$jni_dir/usb-pcm-convert.cpp" \
  "$jni_dir/urb-quarantine.cpp" "$jni_dir/seek-barrier.cpp" "$jni_dir/usb-glitch.cpp" "$jni_dir/usb-second-ring.cpp" \
  "$jni_dir/usb-payload-validation.cpp" \
  -o "$feedback_output" -Wl,--wrap=free -Wl,--wrap=calloc -nostdlib++ "$libcxx_lib" "$libcxxabi_lib" -lpthread -lm
"$feedback_output"
