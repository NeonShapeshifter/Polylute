import os
import tempfile
import unittest
import zipfile

import check_release_surface as check


def archive(entries):
    handle, path = tempfile.mkstemp(suffix=".aab")
    os.close(handle)
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return path


WANTED = [("class", "dap/admin/NoiseMark"), ("action", "com.lostf1sh.pixelplayeross.debug.SET_VOLUME"), ("row text", "Mark noise now")]


class CheckReleaseSurface(unittest.TestCase):
    def tearDown(self):
        for path in getattr(self, "paths", []):
            os.unlink(path)

    def run_scan(self, entries):
        path = archive(entries)
        self.paths = [path]
        return check.scan(path, WANTED)

    def test_a_clean_bundle_has_nothing(self):
        hits, inspected = self.run_scan({"base/dex/classes.dex": b"Lcom/lostf1sh/pixelplayeross/MainActivity;", "base/resources.pb": b"Polylute"})
        self.assertEqual(hits, [])
        self.assertEqual(inspected, 2)

    def test_a_class_an_action_and_a_row_are_each_found(self):
        hits, _ = self.run_scan({
            "base/dex/classes.dex": b"Lcom/lostf1sh/pixelplayeross/dap/admin/NoiseMark;",
            "base/manifest/AndroidManifest.xml": b"com.lostf1sh.pixelplayeross.debug.SET_VOLUME",
            "base/resources.pb": b"..Mark noise now..",
        })
        self.assertEqual({kind for _, kind, _ in hits}, {"class", "action", "row text"})

    def test_a_string_in_the_utf16_pool_of_an_apk_is_found(self):
        hits, _ = self.run_scan({"resources.arsc": "Mark noise now".encode("utf-16-le")})
        self.assertEqual([needle for _, _, needle in hits], ["Mark noise now"])

    def test_files_that_are_not_code_or_resources_are_not_read(self):
        hits, inspected = self.run_scan({"assets/readme.txt": b"Mark noise now"})
        self.assertEqual((hits, inspected), ([], 0))

    def test_the_needles_come_from_the_debug_manifest_and_the_resources(self):
        wanted = check.needles(check.DEFAULT_DEBUG_MANIFEST, check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res")
        actions = [needle for kind, needle in wanted if kind == "action"]
        self.assertIn("com.lostf1sh.pixelplayeross.debug.SET_DAC_EQ_WRITES_ALLOWED", actions)
        self.assertIn("com.lostf1sh.pixelplayeross.debug.MARK_NOISE", actions)
        self.assertGreater(len([1 for kind, _ in wanted if kind == "row text"]), 3)   # the noise row and the crash row, in three languages


class CheckOldProductName(unittest.TestCase):
    """B38 (E2): the artifact carries no string that says the product is PixelPlayerOSS."""

    def tearDown(self):
        for path in getattr(self, "paths", []):
            os.unlink(path)

    def scan_real_needles(self, entries):
        path = archive(entries)
        self.paths = [path]
        wanted = check.needles(check.DEFAULT_DEBUG_MANIFEST, check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res")
        return check.scan(path, wanted)[0]

    def test_the_old_name_is_among_the_things_looked_for(self):
        wanted = check.needles(check.DEFAULT_DEBUG_MANIFEST, check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res")
        self.assertIn(("old product name", "PixelPlayerOSS"), wanted)

    def test_a_channel_name_in_the_resources_a_message_in_the_dex_and_a_pooled_string_of_an_apk_are_found(self):
        hits = self.scan_real_needles({
            "base/resources.pb": b"..PixelPlayerOSS Music Playback..",
            "base/dex/classes.dex": b"Your PixelPlayerOSS backup was created successfully.",
            "resources.arsc": "PixelPlayerOSS_Backup_%1$d.pxpl".encode("utf-16-le"),
        })
        self.assertEqual({(name, kind) for name, kind, _ in hits},
                         {("base/resources.pb", "old product name"), ("base/dex/classes.dex", "old product name"), ("resources.arsc", "old product name")})

    def test_the_credit_and_the_identifiers_are_not_the_old_name(self):
        hits = self.scan_real_needles({
            "base/resources.pb": b"To PixelPlayer, for a beautiful app worth falling in love with. Theme.PixelPlayer pixelplayer_music_channel",
            "base/dex/classes.dex": b"Lcom/lostf1sh/pixelplayeross/PixelPlayerApplication; PixelPlayerGlanceWidget MusicService_PixelPlayer",
        })
        self.assertEqual(hits, [])

    def test_the_main_command_fails_on_the_old_name_and_passes_without_it(self):
        clean = archive({"base/dex/classes.dex": b"Lcom/x;", "base/resources.pb": b"Polylute Music Playback"})
        dirty = archive({"base/dex/classes.dex": b"Lcom/x;", "base/resources.pb": b"PixelPlayerOSS Music Playback"})
        self.paths = [clean, dirty]
        self.assertEqual(check.main([clean]), 0)
        self.assertEqual(check.main([dirty]), 1)


class CheckAssets(unittest.TestCase):
    """B37 (C1): an artifact carries under `assets/` only what the app and its libraries read."""

    def tearDown(self):
        for path in getattr(self, "paths", []):
            os.unlink(path)

    def stray(self, entries):
        path = archive(entries)
        self.paths = [path]
        return check.unexpected_assets(path)

    def test_the_allowed_files_in_a_bundle_and_in_an_apk_are_clean(self):
        bundle = {"base/assets/" + name: b"x" for name in check.ALLOWED_ASSETS}
        apk = {"assets/" + name: b"x" for name in check.ALLOWED_ASSETS}
        self.assertEqual(self.stray(bundle), [])
        self.assertEqual(self.stray(apk), [])

    def test_the_stray_script_is_found_in_a_bundle_and_in_an_apk(self):
        self.assertEqual(self.stray({"base/assets/vm_new.js": b"(function(){})()", "base/assets/licenses/OFL.txt": b"x"}), ["base/assets/vm_new.js"])
        self.assertEqual(self.stray({"assets/vm_new.js": b"x"}), ["assets/vm_new.js"])

    def test_a_file_in_a_feature_module_is_judged_the_same_way(self):
        self.assertEqual(self.stray({"feature/assets/extra.bin": b"x"}), ["feature/assets/extra.bin"])

    def test_the_baseline_profile_of_a_signed_apk_is_not_a_stray(self):
        apk = {"assets/dexopt/baseline.prof": b"x", "assets/dexopt/baseline.profm": b"x", "assets/dexopt/other.bin": b"x"}
        self.assertEqual(self.stray(apk), ["assets/dexopt/other.bin"])

    def test_the_bundles_own_assets_index_is_not_an_asset(self):
        self.assertEqual(self.stray({"base/assets.pb": b"x", "base/resources.pb": b"x", "base/dex/classes.dex": b"x"}), [])

    def test_a_directory_entry_is_not_an_asset(self):
        self.assertEqual(self.stray({"base/assets/": b"", "base/assets/licenses/": b""}), [])

    def test_the_main_command_fails_on_a_stray_asset_and_passes_without_one(self):
        clean = archive({"base/dex/classes.dex": b"Lcom/x;", "base/assets/licenses/LICENSE": b"x"})
        dirty = archive({"base/dex/classes.dex": b"Lcom/x;", "base/assets/vm_new.js": b"x"})
        self.paths = [clean, dirty]
        self.assertEqual(check.main([clean]), 0)
        self.assertEqual(check.main([dirty]), 1)

    def test_the_sources_assets_are_all_on_the_allowlist(self):
        assets = check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/assets"
        found = {p.relative_to(assets).as_posix() for p in assets.rglob("*") if p.is_file()}
        self.assertEqual(found, {"licenses/LICENSE", "licenses/OFL.txt", "licenses/THIRD_PARTY_NOTICES.md"})
        self.assertLessEqual(found, set(check.ALLOWED_ASSETS))


if __name__ == "__main__":
    unittest.main()
