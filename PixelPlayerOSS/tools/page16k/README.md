# 16 KB page-size check (B14-5, audit A-07 / C-08; RELRO rule corrected in B35, RT-04)

```
python3 tools/page16k/check_page_size.py \
    --zipalign ~/Android/Sdk/build-tools/37.0.0/zipalign \
    app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk \
    app/build/outputs/bundle/release/app-release.aab
```

It applies the checks of the Android guide (developer.android.com/guide/practices/page-sizes) to every
`arm64-v8a` and `x86_64` library:

1. every `PT_LOAD` has `p_align` of at least 16 KB;
2. **no writable data other than the RELRO itself lies in the pages the linker protects.** After relocation the dynamic linker makes
   `[page_start(RELRO start), page_end(RELRO end))` read-only, with the *runtime* page size. A RELRO that ends mid-page therefore also
   protects whatever else is in the rest of that 16 KB page; that is the guide's non-compliant case, and it crashes the app with a segmentation
   fault the first time the data is written. The checker reads the `PT_LOAD` map, rounds the RELRO out to the page size and fails when a writable
   `PT_LOAD` (the part of it that is not the RELRO) intersects that range, before or after the RELRO. A RELRO whose end is not a multiple of
   16 KB but which has its own `PT_LOAD`, with the next writable data beyond the rounded end, is **valid**: it is reported as a `note`, not a failure;
3. in an APK, uncompressed `.so` files start on a 16 KB boundary (`zipalign -c -P 16 4`).

Until B35 the second rule was "the RELRO end is a multiple of 0x4000", which rejected layouts the guide itself accepts (the formula alone does not
invalidate a RELRO; the data that shares the rounded tail does).
`test_check_page_size.py` pins the rule against hand-built ELF images, the program headers of the three prebuilts the old rule rejected, and
synthetic layouts where writable data does share the tail (including one byte inside the rounded end, and the page before the RELRO starts).

## State on 2026-10-20 (B35, release bundle of that day)

All ten 64-bit libraries pass: LOAD alignment 0x4000 in every one, and no writable data in any RELRO's protected pages.

| Library | Source | RELRO | Next writable `PT_LOAD` | Verdict |
| --- | --- | --- | --- | --- |
| `libdecent_usb_audio.so` | this repo (`decent-usb-audio-driver`) | ends on a 16 KB boundary | n/a | ok |
| `libflacJNI.so` | this repo (`decent-media3-decoder-flac`) | ends on a 16 KB boundary | n/a | ok |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path` (Compose) | ends 0x6000, protected to 0x8000 | arm64 starts 0x9ed8, x86_64 0x9f70 | ok (note) |
| `libtaglib.so` | `io.github.kyant0:taglib:1.0.6` | arm64 ends 0x136000, protected to 0x138000; x86_64 ends 0x139000, to 0x13c000 | arm64 0x139bf8, x86_64 0x13ccf0 | ok (note) |
| `libffmpegJNI.so` | `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1` | arm64 ends 0x169000, protected to 0x16c000 | arm64 0x16c670 | ok (note) |

So the three prebuilts the old checker called failures **do not need rebuilding for the RELRO**: the earlier statement that they had to be rebuilt from
source with `-Wl,-z,max-page-size=16384` is withdrawn. This is a static check of the ELF layout; it does not replace running the app on a 16 KB-page
device or emulator (launch, decode, metadata read), which has not been done. `libffmpegJNI.so` is also GPL-3.0 and its corresponding source and build
script have to be offered with a release (audit C-07); that is a licensing item, not a page-size one.

`zipalign -c -P 16 4` passes on the release APK.
