#!/usr/bin/env python3
"""The release build shows no debug surface (B35, R6).

Reads the artifact that ships (an AAB or an APK) and fails when it carries something that only a debug build may have:

  * a class of the debug source set (the `SET_*` receiver, the noise mark and its provider, the button-sheet activity);
  * an action of the debug bridge (`com.lostf1sh.pixelplayeross.debug.*`: every `SET_*` override, the DAC EQ write override, the probes), taken from
    `app/src/debug/AndroidManifest.xml` so a new action is covered without touching this file;
  * the text of a debug row: "Mark noise now" (and its Spanish and Swedish), the test crash;
  * (B37) an `assets/` file that is not on the allowlist below: what the app reads, and what its libraries read;
  * (B38) the old product name, `PixelPlayerOSS`, in any string the artifact carries (a channel name, a file name, a message). The credits
    say "PixelPlayer" without the "OSS" and stay; the package, the classes and the actions are spelled `pixelplayeross` or `PixelPlayer...`
    and are not the name.

Where it looks: every `.dex`, the resource table (`resources.pb` in a bundle, `resources.arsc` in an APK) and the manifests, as UTF-8 and as
UTF-16LE (the two encodings those files use for strings). R8 removes code that `BuildConfig.DEBUG` makes unreachable and the resource shrinker then
removes the strings only that code used, so a clean release does not have them; a debug build does, which is the control (run it on the debug APK
and it must fail).

    check_release_surface.py app/build/outputs/bundle/release/app-release.aab
    check_release_surface.py --debug-manifest app/src/debug/AndroidManifest.xml some.apk

Exit status 0 when nothing was found, 1 when something was, 2 when there was nothing to check.
"""
import argparse
import pathlib
import re
import sys
import zipfile

DEBUG_CLASSES = (
    "dap/admin/DapDebugPolicyReceiver",
    "dap/admin/NoiseMark",
    "dap/admin/DebugHooksProvider",
    "ui/ds/DapButtonSheetActivity",
)

# The text of the rows that exist only for a debug build. The test crash's strings are the ones `setcat_trigger_crash_*` carries.
DEBUG_TEXT = (
    "Mark noise now", "Marcar ruido ahora", "Markera brus nu",
)

# B37 (C1): the files the artifact may carry under `assets/` (`base/assets/` in a bundle). A file in `assets/` ships whether or not any code asks
# for it; `vm_new.js`, 21 KB of obfuscated JavaScript that nothing read, did. The first three are the Licenses screen's documents, the only assets
# the app opens (`LicensesScreen.kt`; `AppAssetsBoundaryTest` pins the source side). The last two are not ours: OkHttp's Public Suffix List and
# the Media3 MIDI decoder's wave table, which those libraries open themselves. A library that brings a new asset fails here until it is named.
# The `dexopt/` pair is the baseline profile AGP writes into an APK (not into a bundle, where it lives under `BUNDLE-METADATA/`); ART and
# ProfileInstaller read it at install.
ALLOWED_ASSETS = (
    "licenses/LICENSE",
    "licenses/OFL.txt",
    "licenses/THIRD_PARTY_NOTICES.md",
    "PublicSuffixDatabase.list",
    "sonivox_wave_data.dat",
    "dexopt/baseline.prof",
    "dexopt/baseline.profm",
)

# B38 (E2): the product was PixelPlayerOSS before it was Polylute. Nothing the app shows may say it; the thanks and the licence notice name
# "PixelPlayer" and "PixelPlayerOSS" as the projects Polylute was made from, but the first is in a resource that spells it without "OSS" and
# the second is in `assets/licenses/`, which this scan does not read (`AppAssetsBoundaryTest` and `LicenseDocumentTest` pin that file).
OLD_PRODUCT_NAMES = ("PixelPlayerOSS",)

DEFAULT_DEBUG_MANIFEST = pathlib.Path(__file__).resolve().parents[2] / "app/src/debug/AndroidManifest.xml"


def debug_actions(manifest_path):
    """Every `com.lostf1sh.pixelplayeross.debug.*` action the debug manifest declares."""
    text = pathlib.Path(manifest_path).read_text(encoding="utf-8")
    return sorted(set(re.findall(r'android:name="(com\.lostf1sh\.pixelplayeross\.debug\.[A-Za-z0-9_.]+)"', text)))


def crash_texts(res_dir):
    """The three languages' title and subtitle of the test crash row, read from the resources so they cannot drift."""
    found = []
    for values in sorted(pathlib.Path(res_dir).glob("values*/strings_settings.xml")):
        for name in ("setcat_trigger_crash_title", "setcat_trigger_crash_subtitle"):
            m = re.search(r'<string name="%s"[^>]*>(.*?)</string>' % name, values.read_text(encoding="utf-8"), re.S)
            if m:
                found.append(m.group(1).replace("\\'", "'").replace("&amp;", "&"))
    return found


def needles(debug_manifest, res_dir):
    result = [("class", name) for name in DEBUG_CLASSES]
    result += [("action", action) for action in debug_actions(debug_manifest)]
    result += [("row text", text) for text in DEBUG_TEXT]
    result += [("row text", text) for text in crash_texts(res_dir)]
    result += [("old product name", name) for name in OLD_PRODUCT_NAMES]
    return result


def is_inspected(name):
    base = name.rsplit("/", 1)[-1]
    return name.endswith(".dex") or base in ("resources.pb", "resources.arsc", "AndroidManifest.xml")


def scan(path, wanted):
    """[(file in the archive, kind, needle)] for every needle found in what the archive ships."""
    hits = []
    inspected = 0
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not is_inspected(info.filename):
                continue
            inspected += 1
            data = archive.read(info)
            for kind, needle in wanted:
                if needle.encode("utf-8") in data or needle.encode("utf-16-le") in data:
                    hits.append((info.filename, kind, needle))
    return hits, inspected


def asset_of(entry):
    """The path under `assets/` of an archive entry (`assets/x` in an APK, `<module>/assets/x` in a bundle), or None if it is not an asset."""
    m = re.match(r"^(?:[^/]+/)?assets/(.+)$", entry)
    return m.group(1) if m and not entry.endswith("/") else None


def unexpected_assets(path, allowed=ALLOWED_ASSETS):
    """[entry] for every file under `assets/` that is not on the allowlist."""
    with zipfile.ZipFile(path) as archive:
        return sorted(
            info.filename for info in archive.infolist()
            if asset_of(info.filename) is not None and asset_of(info.filename) not in allowed
        )


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+")
    parser.add_argument("--debug-manifest", default=str(DEFAULT_DEBUG_MANIFEST))
    parser.add_argument("--res", default=str(DEFAULT_DEBUG_MANIFEST.parents[1] / "main/res"))
    args = parser.parse_args(argv)

    wanted = needles(args.debug_manifest, args.res)
    failed = False
    inspected_total = 0
    for path in args.files:
        hits, inspected = scan(path, wanted)
        inspected_total += inspected
        print(f"== {path}: {inspected} files read, {len(wanted)} things looked for")
        for name, kind, needle in hits:
            print(f"  FAIL {name}: {kind} {needle!r}")
        failed = failed or bool(hits)
        if not hits:
            print("  ok   no debug class, action or row text, and no old product name")
        strays = unexpected_assets(path)
        for entry in strays:
            print(f"  FAIL {entry}: an asset nothing is known to read")
        failed = failed or bool(strays)
        if not strays:
            print(f"  ok   assets: only the {len(ALLOWED_ASSETS)} allowed files")
    if inspected_total == 0:
        print("nothing to check: no dex, resource table or manifest found", file=sys.stderr)
        return 2
    print("release surface:", "FAILED" if failed else "clean")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
