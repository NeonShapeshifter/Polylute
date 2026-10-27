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


WANTED = [("class", "dap/admin/DapDebugPolicyReceiver"), ("action", "com.lostf1sh.pixelplayeross.debug.SET_VOLUME"), ("row text", "Trigger a test crash")]

# What a release carries (B39-S2): the noise mark's row and its native call. A toy archive that is to be clean has to have them.
NOISE_MARK = {"base/resources.pb": b"Mark noise now", "base/dex/classes.dex": b"nativeGetSecondSummaries nativeUsbResetForRebind"}


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
            "base/dex/classes.dex": b"Lcom/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver;",
            "base/manifest/AndroidManifest.xml": b"com.lostf1sh.pixelplayeross.debug.SET_VOLUME",
            "base/resources.pb": b"..Trigger a test crash..",
        })
        self.assertEqual({kind for _, kind, _ in hits}, {"class", "action", "row text"})

    def test_a_string_in_the_utf16_pool_of_an_apk_is_found(self):
        hits, _ = self.run_scan({"resources.arsc": "Trigger a test crash".encode("utf-16-le")})
        self.assertEqual([needle for _, _, needle in hits], ["Trigger a test crash"])

    def test_files_that_are_not_code_or_resources_are_not_read(self):
        hits, inspected = self.run_scan({"assets/readme.txt": b"Trigger a test crash"})
        self.assertEqual((hits, inspected), ([], 0))

    def test_the_needles_come_from_the_debug_manifest_and_the_resources(self):
        wanted = check.needles(check.DEFAULT_DEBUG_MANIFEST, check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res")
        actions = [needle for kind, needle in wanted if kind == "action"]
        self.assertIn("com.lostf1sh.pixelplayeross.debug.SET_DAC_EQ_WRITES_ALLOWED", actions)
        self.assertIn("com.lostf1sh.pixelplayeross.debug.MARK_NOISE", actions)   # the broadcast stays debug; the row it shares an action with does not
        self.assertGreater(len([1 for kind, _ in wanted if kind == "row text"]), 3)   # the crash row's title and subtitle, in three languages
        self.assertNotIn(("row text", "Mark noise now"), wanted)

    def test_development_session_profiling_is_rejected_in_a_release(self):
        path = archive({"base/dex/classes.dex": check.DEBUG_INSTRUMENTATION[0].encode()})
        self.paths = [path]
        wanted = check.needles(check.DEFAULT_DEBUG_MANIFEST, check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res")
        hits, _ = check.scan(path, wanted)
        self.assertIn(("base/dex/classes.dex", "development instrumentation", check.DEBUG_INSTRUMENTATION[0]), hits)

    def test_every_class_of_the_debug_source_set_is_looked_for_without_naming_it_here(self):
        wanted = check.needles(check.DEFAULT_DEBUG_MANIFEST, check.DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res")
        classes = {needle for kind, needle in wanted if kind == "class"}
        for name in ("DapDebugPolicyReceiver", "DirectPathCapture", "DacEqDebugCommands", "DapButtonSheetActivity", "DualPlayerEngineDebugEntryPoint"):
            self.assertTrue(any(c.endswith("/" + name) for c in classes), name)
        # B39-S2: the mark is main code now, so its new class is not a debug class
        self.assertNotIn("com/lostf1sh/pixelplayeross/data/diagnostics/NoiseMark", classes)
        self.assertNotIn("data/diagnostics/NoiseMark", classes)


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
        clean = archive({"base/dex/classes.dex": b"Lcom/x; nativeGetSecondSummaries nativeUsbResetForRebind", "base/resources.pb": b"Polylute Music Playback Mark noise now"})
        dirty = archive({"base/dex/classes.dex": b"Lcom/x; nativeGetSecondSummaries nativeUsbResetForRebind", "base/resources.pb": b"PixelPlayerOSS Music Playback Mark noise now"})
        self.paths = [clean, dirty]
        self.assertEqual(check.main([clean]), 0)
        self.assertEqual(check.main([dirty]), 1)


class CheckNoiseMarkIsInTheRelease(unittest.TestCase):
    """B39-S2: the release carries the noise mark, and only that, of what the debug build has."""

    def tearDown(self):
        for path in getattr(self, "paths", []):
            os.unlink(path)

    def test_a_release_with_the_row_and_the_native_call_is_clean(self):
        clean = archive(NOISE_MARK)
        self.paths = [clean]
        self.assertEqual(check.missing_required(clean), [])
        self.assertEqual(check.main([clean]), 0)

    def test_a_release_that_lost_the_row_or_the_native_call_fails(self):
        no_row = archive({"base/resources.pb": b"Polylute", "base/dex/classes.dex": b"nativeGetSecondSummaries nativeUsbResetForRebind"})
        no_call = archive({"base/resources.pb": b"Mark noise now", "base/dex/classes.dex": b"Lcom/x;"})
        self.paths = [no_row, no_call]
        self.assertEqual(check.missing_required(no_row), [("noise mark row", "Mark noise now")])
        self.assertEqual(
            check.missing_required(no_call),
            [("noise mark native call", "nativeGetSecondSummaries"), ("DAC release reset native call", "nativeUsbResetForRebind")],
        )
        self.assertEqual(check.main([no_row]), 1)
        self.assertEqual(check.main([no_call]), 1)

    def test_a_release_that_lost_only_the_dac_reset_call_fails(self):
        # B39-A7: the reset that hands a DAC back to Android is bound by name from the native library, like the noise mark's call
        no_reset = archive({"base/resources.pb": b"Mark noise now", "base/dex/classes.dex": b"nativeGetSecondSummaries"})
        self.paths = [no_reset]
        self.assertEqual(check.missing_required(no_reset), [("DAC release reset native call", "nativeUsbResetForRebind")])
        self.assertEqual(check.main([no_reset]), 1)

    def test_the_noise_mark_alone_does_not_excuse_a_debug_receiver_or_a_debug_action(self):
        receiver = archive({**NOISE_MARK, "base/dex/classes.dex": b"nativeGetSecondSummaries nativeUsbResetForRebind Lcom/lostf1sh/pixelplayeross/dap/admin/DapDebugPolicyReceiver;"})
        action = archive({**NOISE_MARK, "base/manifest/AndroidManifest.xml": b"com.lostf1sh.pixelplayeross.debug.SET_VOLUME"})
        marker = archive({**NOISE_MARK, "base/manifest/AndroidManifest.xml": b"com.lostf1sh.pixelplayeross.debug.MARK_NOISE"})
        self.paths = [receiver, action, marker]
        self.assertEqual(check.main([receiver]), 1)
        self.assertEqual(check.main([action]), 1)
        # the shell broadcast is a debug action: a release that declared it would fail, with or without the mark
        self.assertEqual(check.main([marker]), 1)

    def test_the_old_debug_provider_and_hook_coming_back_are_found(self):
        for name in (b"Lcom/lostf1sh/pixelplayeross/dap/admin/DebugHooksProvider;", b"Lcom/lostf1sh/pixelplayeross/data/diagnostics/DebugHooks;"):
            path = archive({**NOISE_MARK, "base/dex/classes.dex": b"nativeGetSecondSummaries nativeUsbResetForRebind " + name})
            self.paths = getattr(self, "paths", []) + [path]
            self.assertEqual(check.main([path]), 1)


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
        clean = archive({**NOISE_MARK, "base/assets/licenses/LICENSE": b"x"})
        dirty = archive({**NOISE_MARK, "base/assets/vm_new.js": b"x"})
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
