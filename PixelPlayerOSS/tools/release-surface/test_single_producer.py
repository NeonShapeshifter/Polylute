"""B45 boundary: absence of a second producer in sources and shipped binaries."""
import os
import pathlib
import tempfile
import unittest
import zipfile

import check_release_surface as release
import check_jni_keeps as jni


class SingleProducer(unittest.TestCase):
    def test_runtime_sources_cmake_and_jni_have_no_retired_producer(self):
        root = pathlib.Path(os.environ.get("B45_SOURCE_ROOT", pathlib.Path(__file__).resolve().parents[3]))
        roots = [root / "PixelPlayerOSS/app/src/main", root / "PixelPlayerOSS/app/src/debug", root / "decent-player/libs"]
        hits = []
        for source in roots:
            for path in source.rglob("*"):
                if not path.is_file() or not (path.suffix in {".kt", ".cpp", ".cc", ".h", ".xml"} or path.name == "CMakeLists.txt"):
                    continue
                if "/build/" in str(path) or "/.cxx/" in str(path) or "/src/test/" in str(path):
                    continue
                data = path.read_text(errors="replace")
                for needle in release.RETIRED_PRODUCER:
                    if needle in data or needle in path.name:
                        hits.append(f"{path.relative_to(root)} contains {needle}")
                # Retired preference files and keys must never be consulted, migrated or rewritten.
                for key in ("debug_native_engine", "force_native_engine", "debug_pristine_pipeline", "force_exoplayer_pipeline"):
                    if key in data:
                        hits.append(f"{path.relative_to(root)} refers to retired preference {key}")
        self.assertEqual(hits, [], "\n".join(hits))

    def test_old_binary_producer_is_rejected_in_each_shipped_surface(self):
        for entry in ("base/lib/arm64-v8a/libdecent_usb_audio.so", "base/dex/classes.dex", "base/resources.pb"):
            for needle in release.RETIRED_PRODUCER:
                with tempfile.NamedTemporaryFile(suffix=".aab") as file:
                    with zipfile.ZipFile(file.name, "w") as archive:
                        archive.writestr(entry, needle.encode())
                    self.assertEqual(release.retired_producer_hits(file.name), [(entry, needle)])

    def test_flac_decoder_and_writer_still_export_their_jni_functions(self):
        exports = []
        for path in jni.native_files(jni.default_native_roots()):
            exports.extend(jni.scan_source(path)[1])
        classes = {export.cls for export in exports}
        self.assertIn("com/decent/usbaudio/UsbAudioStream", classes)
        self.assertTrue(any("FlacDecoderJni" in name for name in classes))
        self.assertFalse(any("NativeAudioEngine" in name for name in classes))

    def test_release_entry_records_the_single_producer(self):
        root = pathlib.Path(os.environ.get("B45_SOURCE_ROOT", pathlib.Path(__file__).resolve().parents[3]))
        changelog = (root / "PixelPlayerOSS/CHANGELOG.md").read_text()
        self.assertIn("## 1.2.0\n", changelog)
        self.assertLess(changelog.index("## 1.2.0\n"), changelog.index("## 1.1.1"))
        self.assertIn("Purity has one playback path: the old second FLAC player is gone.", changelog.split("## 1.1.1")[0])
        properties = (root / "PixelPlayerOSS/gradle.properties").read_text()
        self.assertIn("## 1.2.1\n", changelog)
        self.assertLess(changelog.index("## 1.2.1\n"), changelog.index("## 1.2.0\n"))
        self.assertIn("APP_VERSION_NAME=1.2.1", properties)
        self.assertIn("APP_VERSION_CODE=6", properties)
