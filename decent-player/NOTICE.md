# Notice

Copyright (c) 2026 Marcelo Silva. Licensed under the MIT License; the full text is in [LICENSE](LICENSE).

That applies to everything in this directory except the two parts below.

## AndroidX Media

The Java files in `libs/decent-media3-decoder-flac/src/main/java/androidx/media3/decoder/flac/` and the native glue in `libs/decent-media3-decoder-flac/src/main/jni/` (`flac_jni.cc`, `flac_parser.cc`, `include/data_source.h`, `include/flac_parser.h`, `CMakeLists.txt`) come from AndroidX Media. They are Copyright The Android Open Source Project, under the Apache License, Version 2.0. Their headers say so.

## libFLAC

`libs/decent-media3-decoder-flac/src/main/jni/libflac/` is the upstream [xiph/flac](https://github.com/xiph/flac) source, version 1.5.0, under its original BSD-style license: see `COPYING.Xiph` in that directory. Copyright (C) 2000-2009 Josh Coalson and (C) 2011-2025 Xiph.Org Foundation.
