cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# ── libFLAC (reuse xiph/flac source from decoder-flac module) ──
set(FLAC_MODULE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../decent-media3-decoder-flac/src/main/jni")
set(WITH_OGG OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS OFF CACHE BOOL "" FORCE)
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_MANPAGES OFF CACHE BOOL "" FORCE)
add_subdirectory("${FLAC_MODULE_DIR}/libflac"
                 "${CMAKE_CURRENT_BINARY_DIR}/libflac"
                 EXCLUDE_FROM_ALL)

# ── Main library: USB audio driver + native audio engine ──
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    usb-pcm-packetizer.cpp
    usb-pcm-convert.cpp
    urb-quarantine.cpp
    seek-barrier.cpp
    usb-glitch.cpp
    usb-second-ring.cpp
    usb-payload-validation.cpp
    native-audio-engine.cpp
    "${FLAC_MODULE_DIR}/flac_parser.cc"
)

target_include_directories(decent_usb_audio PRIVATE
    "${FLAC_MODULE_DIR}/include"
    "${FLAC_MODULE_DIR}/libflac/include"
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
    FLAC
)

# 16 KB page-size devices (B14-5, audit A-07). NDK r28+ already aligns the LOAD segments to 16 KB
# (max-page-size), but the end of the RELRO segment is still rounded to the system's 4 KB common page size, so it
# ends mid-page on a 16 KB device. Both flags, explicitly, so the result does not depend on the NDK's default.
# Checked on the built APK and AAB with PixelPlayerOSS/tools/page16k/check_page_size.py.
target_link_options(decent_usb_audio PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
