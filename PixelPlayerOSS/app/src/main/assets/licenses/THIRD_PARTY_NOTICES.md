# Third-Party Notices

Polylute is licensed under the GNU General Public License, version 3 or later (GPL-3.0-or-later). The license text is the first entry of this screen. It is also in the LICENSE file of the source repository and at https://www.gnu.org/licenses/gpl-3.0.txt.

Polylute contains the components below. Each one keeps its own license. This is the only notices file of the project: the app shows it under Settings, About, and the source repository points to it.

# Origins

Polylute is a modified version of PixelPlayerOSS and of decent-player, modified in 2026 (GNU GPL, section 5(a)).

| Project | Copyright | License | Source |
| --- | --- | --- | --- |
| PixelPlayerOSS, the application | Copyright (C) 2026 Theo Vilardo, maintained by lostf1sh | GPL-3.0-or-later | https://github.com/lostf1sh/PixelPlayerOSS |
| decent-player: USB audio driver, Media3 wrapper and FLAC decoder integration | Copyright (c) 2026 Marcelo Silva | MIT | https://github.com/Ma145/decent-player |

# Native libraries

These are all the native libraries in the app. `libffmpegJNI.so`, `libtaglib.so` and `libandroidx.graphics.path.so` come ready-built from the Maven artifacts named below; Polylute does not rebuild or change them. `libflacJNI.so` and `libdecent_usb_audio.so` are built from the source repository.

| Library | What it is | License | Version | Source |
| --- | --- | --- | --- | --- |
| `libffmpegJNI.so` | FFmpeg audio decoders: FLAC, ALAC, MP3, AAC, AC-3, E-AC-3, DTS, MLP, TrueHD, A-law and mu-law PCM. From `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1`. | GPL-3.0, as declared by that artifact | libavcodec 60.3.100 (FFmpeg 6.0 branch) | Corresponding source: https://github.com/jellyfin/jellyfin-androidx-media at tag v1.9.0+1. Its submodules are FFmpeg (https://git.ffmpeg.org/ffmpeg.git, branch release/6.0, commit 37c90f55d8b6c3948c79449b04755527b4ae1351) and AndroidX Media (https://github.com/androidx/media, commit 7cc1056f840ce226598d3b990d4a6f7cd17e2831). The decoders are the ones listed in its build.sh. |
| `libtaglib.so` | Audio tag reading and writing. From `io.github.kyant0:taglib:1.0.6`, a JNI wrapper around TagLib. | Wrapper: Apache-2.0. TagLib: LGPL-2.1 or MPL-1.1 | Wrapper 1.0.6. The library does not state its TagLib version; the wrapper pins TagLib at commit 1cbd910c3da4818e835ee6d6e467157366d5f1bb | https://github.com/Kyant0/taglib at tag 1.0.6; TagLib: https://github.com/taglib/taglib |
| `libandroidx.graphics.path.so` | Path maths for Compose shapes. From `androidx.graphics:graphics-path:1.1.0-rc01`, pulled in by `androidx.graphics:graphics-shapes:1.1.0`. | Apache-2.0 | 1.1.0-rc01 | https://android.googlesource.com/platform/frameworks/support |
| `libflacJNI.so` | FLAC decoder. JNI glue taken from AndroidX Media, plus libFLAC. | libFLAC: BSD 3-clause (Xiph.Org Foundation). Glue: Apache-2.0 | libFLAC 1.5.0 (11 February 2025) | In the source repository: decent-player/libs/decent-media3-decoder-flac, with libFLAC in src/main/jni/libflac. Upstream: https://github.com/xiph/flac |
| `libdecent_usb_audio.so` | USB audio class driver and bit-perfect output. | MIT | Built from the source repository | decent-player/libs/decent-usb-audio-driver in the source repository. Upstream: https://github.com/Ma145/decent-player |

# Fonts

The SIL Open Font License 1.1 text, with the copyright notices it requires, is shown in the next section of this screen.

| Asset | Source | License |
| --- | --- | --- |
| Google Sans Flex (`gflex_variable.ttf`) | `https://fonts.google.com/specimen/Google+Sans+Flex/license?preview.script=Latn` | SIL Open Font License 1.1 |
| Roboto Flex (`genre_variable.ttf`) | `https://github.com/googlefonts/roboto-flex` | SIL Open Font License 1.1 |
| Manrope (`manrope_variable.ttf`) | `https://github.com/google/fonts/tree/main/ofl/manrope` | SIL Open Font License 1.1 |
| JetBrains Mono (`jetbrains_mono_variable.ttf`) | `https://github.com/google/fonts/tree/main/ofl/jetbrainsmono` | SIL Open Font License 1.1 |

# Bundled data

| Data | Comes with | License | Source |
| --- | --- | --- | --- |
| Japanese dictionary (`com/atilika/kuromoji/ipadic/*.bin`) | Kuromoji IPADIC 0.9.0 | Kuromoji: Apache-2.0. Dictionary data from mecab-ipadic-2.7.0-20070801 under the NAIST and ICOT notice at the end of this file | https://github.com/atilika/kuromoji |
| Chinese pinyin tables (`pinyindb/`) | pinyin4j 2.5.1 | BSD, as declared in its POM | https://github.com/belerweb/pinyin4j |
| Public Suffix List (`PublicSuffixDatabase.list`) | OkHttp 5.4.0 | Mozilla Public License 2.0 | https://publicsuffix.org |
| MIDI wave table (`sonivox_wave_data.dat`) | `androidx.media3:media3-exoplayer-midi:1.10.1` | Apache-2.0, as declared by that artifact | https://github.com/androidx/media |

# Ported data and protocol facts

| Data | Where | Upstream | License |
| --- | --- | --- | --- |
| FiiO and Snowsky HID device profiles (product ids, report ids, band counts, gain and Q ranges, HID interface numbers, product-name aliases) and the documented FiiO HID GET frame layout | `app/src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/` | WolfEQ, `https://github.com/audioslayer/wolfeq` (`Models/FiioDeviceProfile.cs`, `Services/FiioK13DeviceService.cs`). Its community profile entries are adapted from Pragmatic Audio's DevicePEQ | WolfEQ: MIT. DevicePEQ: 0BSD. Only facts and data were ported; the code is original to this project. |
| FiiO and Snowsky HID write-side protocol facts: the SET frame header and layout, the command ids and data layouts for band, preset, global gain and save, the filter-type codes, the Melody's preset slot ids and its Close EQ id, and the per-model filter-type and gain limits | `app/src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/` (`FiioHidWrites.kt`, `FiioEqReader.kt`, `FiioHidProfiles.kt`, `DacEqModel.kt`) | WolfEQ (`Services/FiioK13DeviceService.cs`: `BuildSetPacket`, `EncodeBand`, `SaveToDeviceAsync`, `SelectUserPresetAsync`, `ReadCurrentEqAsync`; `Models/FiioDeviceProfile.cs`; `Models/EqBand.cs`) and DevicePEQ (`devicePEQ/fiioUsbHidHandler.js`, `devicePEQ/usbDeviceConfig.js`, `devicePEQ/peqConstraintsConfig.json`) | WolfEQ: MIT. DevicePEQ: 0BSD. Only facts and data; every function is original. Each command is cited, with its source, in `FiioHidWrites.kt` and `FiioEqReader.kt`. |
| Thirteen recorded replies of a SNOWSKY Melody, used as test fixtures for the reply parser, the CRC-8/MAXIM check and the band decoder. Not part of the app. | `app/src/test/java/com/lostf1sh/pixelplayeross/data/dac/fiio/FiioProtocolReadTest.kt` | DevicePEQ `tests/captures/fiio_snowsky_melody.json` | 0BSD (device replies; no personal data) |
| The observation that FiiO DACs realise a peaking filter's bandwidth as `Q / A` (`A = 10^(abs(gain)/40)`), used only as the wider of two readings when the app bounds a curve's peak | `app/src/main/java/com/lostf1sh/pixelplayeross/data/dac/fiio/DacEqCurve.kt` | DevicePEQ `devicePEQ/fiioUsbHidHandler.js` (comments on REW measurements of the QX13 and KA17) | 0BSD. A measurement of other people's units, not of the Melody; it is a margin, not a claim. |

# Libraries

These are the libraries compiled into the app, taken from the release runtime classpath. Test and build tools are not listed because they are not shipped.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| AndroidX: Activity, AppCompat, Compose, ConstraintLayout, Core, DataStore, Glance, Graphics, Hilt extensions, Lifecycle, Media, Media3 (ExoPlayer, Session, UI, MIDI, Transformer), Navigation, Paging, Palette, Room, WorkManager and their dependencies | Compose BOM 2026.06.01, Media3 1.10.1, Room 2.8.4, WorkManager 2.11.2, Glance 1.3.0-alpha02 | Apache-2.0 | https://developer.android.com/jetpack and https://github.com/androidx/media |
| JetBrains Compose Multiplatform runtime artifacts (org.jetbrains.compose), pulled in by Wavy Slider and Reorderable | 1.9.3 | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, kotlinx.collections.immutable, JetBrains annotations | Kotlin 2.4.10, coroutines 1.9.0, serialization 1.11.0, collections-immutable 0.5.1 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Material Components for Android | 1.14.0 | Apache-2.0 | https://github.com/material-components/material-components-android |
| Dagger and Hilt | 2.60.1 | Apache-2.0 | https://github.com/google/dagger |
| Guava, Gson, ErrorProne annotations, J2ObjC annotations, JSR-305 | Guava 33.3.1-android, Gson 2.14.0 | Apache-2.0 | https://github.com/google/guava and https://github.com/google/gson |
| Accompanist (drawablepainter, permissions) | 0.37.3 | Apache-2.0 | https://github.com/google/accompanist |
| Core library desugaring (desugar_jdk_libs), compiled into the app as j$ classes | 2.1.5 | GPL-2.0 with the Classpath Exception | https://github.com/google/desugar_jdk_libs |
| OkHttp, Okio, Retrofit with its Gson converter | OkHttp 5.4.0, Okio 3.17.0, Retrofit 3.0.0 | Apache-2.0 | https://square.github.io/okhttp/ and https://github.com/square/retrofit |
| Coil | 2.7.0 | Apache-2.0 | https://github.com/coil-kt/coil |
| Timber | 5.0.1 | Apache-2.0 | https://github.com/JakeWharton/timber |
| JAudiotagger | 3.0.1 | LGPL, version 2.1 or later (the license header of its POM; the POM field says only LGPL) | https://bitbucket.org/ijabz/jaudiotagger |
| Vorbis Java | 0.8 | Apache-2.0 | https://github.com/Gagravarr/VorbisJava |
| Kuromoji (core and IPADIC) | 0.9.0 | Apache-2.0; dictionary data: see the end of this file | https://github.com/atilika/kuromoji |
| pinyin4j | 2.5.1 | BSD, as declared in its POM | https://github.com/belerweb/pinyin4j |
| Wavy Slider | 2.2.0 | Apache-2.0 | https://mahozad.ir/wavy-slider |
| Reorderable | 3.1.0 | Apache-2.0 | https://github.com/Calvin-LL/Reorderable |
| Capturable | 3.0.1 | MIT | https://github.com/PatilShreyas/Capturable/ |
| smooth-corner-rect-android-compose, from JitPack | v1.0.0 | MIT | https://github.com/racra/smooth-corner-rect-android-compose |
| JSyn, from JitPack, pulled in by Media3 MIDI | commit 3f6b44b853bccc0d2e3027104d575fcc5ccb6d4e | Apache-2.0 | https://github.com/philburk/jsyn |
| JSch (mwiede fork) with JZlib and jBCrypt, pulled in by the USB audio wrapper's SFTP code, which Polylute does not call | 0.2.23 | JSch and JZlib: BSD 3-clause. jBCrypt: ISC. The three license texts are in the app's META-INF | https://github.com/mwiede/jsch |
| javax.inject, Jakarta Inject, JSpecify, Checker Framework annotations | 1, 2.0.1, 1.0.0, 3.43.0 | Apache-2.0; Checker Framework annotations: MIT | https://github.com/jakartaee/inject and https://github.com/jspecify/jspecify and https://checkerframework.org |

# mecab-ipadic notice

The Japanese dictionary above is data from mecab-ipadic-2.7.0-20070801. Its notice, as given in the Kuromoji IPADIC artifact:

Nara Institute of Science and Technology (NAIST),
the copyright holders, disclaims all warranties with regard to this
software, including all implied warranties of merchantability and
fitness, in no event shall NAIST be liable for
any special, indirect or consequential damages or any damages
whatsoever resulting from loss of use, data or profits, whether in an
action of contract, negligence or other tortuous action, arising out
of or in connection with the use or performance of this software.

A large portion of the dictionary entries
originate from ICOT Free Software.  The following conditions for ICOT
Free Software applies to the current dictionary as well.

Each User may also freely distribute the Program, whether in its
original form or modified, to any third party or parties, PROVIDED
that the provisions of Section 3 ("NO WARRANTY") will ALWAYS appear
on, or be attached to, the Program, which is distributed substantially
in the same form as set out herein and that such intended
distribution, if actually made, will neither violate or otherwise
contravene any of the laws and regulations of the countries having
jurisdiction over the User or the intended distribution itself.

NO WARRANTY

The program was produced on an experimental basis in the course of the
research and development conducted during the project and is provided
to users as so produced on an experimental basis.  Accordingly, the
program is provided without any warranty whatsoever, whether express,
implied, statutory or otherwise.  The term "warranty" used herein
includes, but is not limited to, any warranty of the quality,
performance, merchantability and fitness for a particular purpose of
the program and the nonexistence of any infringement or violation of
any right of any third party.

Each user of the program will agree and understand, and be deemed to
have agreed and understood, that there is no warranty whatsoever for
the program and, accordingly, the entire risk arising from or
otherwise connected with the program is assumed by the user.

Therefore, neither ICOT, the copyright holder, or any other
organization that participated in or was otherwise related to the
development of the program and their respective officials, directors,
officers and other employees shall be held liable for any and all
damages, including, without limitation, general, special, incidental
and consequential damages, arising out of or otherwise in connection
with the use or inability to use the program or any product, material
or result produced or otherwise obtained by using the program,
regardless of whether they have been advised of, or otherwise had
knowledge of, the possibility of such damages at any time during the
project or thereafter.  Each user will be deemed to have agreed to the
foregoing by his or her commencement of use of the program.  The term
"use" as used herein includes, but is not limited to, the use,
modification, copying and distribution of the program and the
production of secondary products from the program.

In the case where the program, whether in its original form or
modified, was distributed or delivered to or received by a user from
any person, organization or entity other than ICOT, unless it makes or
grants independently of ICOT any specific warranty to the user in
writing, such person, organization or entity, will also be exempted
from and not be held liable to the user for any such damages as noted
above as far as the program is concerned.
