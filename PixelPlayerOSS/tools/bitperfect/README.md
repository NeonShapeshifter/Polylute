# Bit-perfect validation tools

Generate deterministic 12-second WAV vectors and host reference manifests:

```sh
python3 tools/bitperfect/bitperfect_reference.py generate /tmp/bitperfect-vectors
```

Analyze any source into canonical PCM and the expected Decent USB slot layout:

```sh
python3 tools/bitperfect/bitperfect_reference.py analyze track.flac \
  --source-bits 24 --usb-slot-bits 32 --output track.reference.json
```

Compare that reference with a manifest pulled from PixelPlayer's debug-only
observer:

```sh
python3 tools/bitperfect/bitperfect_reference.py compare \
  track.reference.json track.capture.json --output track.comparison.json
```

The observer hashes only audio bytes from URBs accepted by
`USBDEVFS_SUBMITURB`. It is not an external electrical USB capture. It is
disabled by default and can only be controlled by the shell-protected receiver
present in debug builds. Raw music is never written; only aggregate and 1 MiB
block hashes are persisted in app-private storage.
