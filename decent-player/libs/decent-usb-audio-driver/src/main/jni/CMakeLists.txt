cmake_minimum_required(VERSION 3.22.1)
project("decent_usb_audio" C CXX)

set(CMAKE_CXX_STANDARD 11)

# USB output and its transport helpers.
add_library(decent_usb_audio SHARED
    usb-audio-output.cpp
    usb-pcm-packetizer.cpp
    usb-transport-plan-jni.cpp
    usb-pcm-convert.cpp
    urb-quarantine.cpp
    seek-barrier.cpp
    usb-glitch.cpp
    usb-second-ring.cpp
    usb-cpu-watch.cpp
    usb-payload-validation.cpp
)

find_library(log-lib log)
target_link_libraries(decent_usb_audio
    ${log-lib}
)

# 16 KB page-size devices (B14-5, audit A-07). NDK r28+ already aligns the LOAD segments to 16 KB
# (max-page-size), but the end of the RELRO segment is still rounded to the system's 4 KB common page size, so it
# ends mid-page on a 16 KB device. Both flags, explicitly, so the result does not depend on the NDK's default.
# Checked on the built APK and AAB with PixelPlayerOSS/tools/page16k/check_page_size.py.
target_link_options(decent_usb_audio PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)
