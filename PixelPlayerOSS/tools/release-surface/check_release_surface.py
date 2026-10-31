#!/usr/bin/env python3
"""The release build shows no debug surface (B35, R6).

Reads the artifact that ships (an AAB or an APK) and fails when it carries something that only a debug build may have:

  * a class of the debug source set (the `SET_*` receiver, the capture and the DAC EQ commands, the button-sheet activity), read from
    `app/src/debug/java` so a new debug class is covered without touching this file;
  * an action of the debug bridge (`com.lostf1sh.pixelplayeross.debug.*`: every `SET_*` override, the DAC EQ write override, the probes), taken from
    `app/src/debug/AndroidManifest.xml` so a new action is covered without touching this file;
  * the text of a debug row: the test crash;
  * (B39-S2) the opposite, for the one thing of the old debug bridge that a release is meant to have: "Mark noise now" (the row, in the More sheet and
    in Maintenance) and the JNI name of the output's one-second summaries must be there. A release that lost them cannot capture the next "shh";
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

# Debug classes that must stay out whether or not their source is at hand (the check below adds every class the debug source set declares).
# B39-S2: the noise mark and its provider are not in this list any more: the mark moved to the main source set (`data/diagnostics/NoiseMark`),
# the provider and the `DebugHooks` object it installed are gone, and the two old names stay here so that neither can come back unnoticed.
DEBUG_CLASSES = (
    "dap/admin/DapDebugPolicyReceiver",
    "dap/admin/NoiseMark",
    "dap/admin/DebugHooksProvider",
    "data/diagnostics/DebugHooks",
    "ui/ds/DapButtonSheetActivity",
)

# The text of the rows that exist only for a debug build is the test crash's (`setcat_trigger_crash_*`, read from the resources).
DEBUG_TEXT = ()

# Development-only session timing must be eliminated by R8, including its looper probe.
DEBUG_INSTRUMENTATION = (
    "session publication %d ms (session %d ms, controllers %d ms) items=%d reason=%d",
    "session publication waited %d ms behind: %s",
)

# B39-S2: what a release must carry. R8 renames the class, so the proof is what it cannot rename: the row's text (a resource the shrinker keeps
# only while code uses it) and the JNI name the native library binds the output's summaries to.
REQUIRED = (
    ("noise mark row", "Mark noise now"),
    ("noise mark native call", "nativeGetSecondSummaries"),
    # B39-A7: the reset that gives a DAC back to Android is bound by name too; a shrinker that renamed it would fail at the moment of the release
    ("DAC release reset native call", "nativeUsbResetForRebind"),
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

# B45: retired second producer. Inspect native binaries as well as dex/resources.
RETIRED_PRODUCER = (
    "NativeAudioEngine", "native-audio-engine", "SET_FORCE_NATIVE_ENGINE", "NativeEnginePolicy",
)

def retired_producer_hits(path):
    hits = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not (is_inspected(info.filename) or info.filename.endswith(".so")):
                continue
            data = archive.read(info)
            for needle in RETIRED_PRODUCER:
                if needle.encode() in data or needle.encode("utf-16-le") in data:
                    hits.append((info.filename, needle))
    return hits

DEFAULT_DEBUG_MANIFEST = pathlib.Path(__file__).resolve().parents[2] / "app/src/debug/AndroidManifest.xml"

def debug_actions(manifest_path):
    """Every `com.lostf1sh.pixelplayeross.debug.*` action the debug manifest declares."""
    text = pathlib.Path(manifest_path).read_text(encoding="utf-8")
    return sorted(set(re.findall(r'android:name="(com\.lostf1sh\.pixelplayeross\.debug\.[A-Za-z0-9_.]+)"', text)))

def debug_source_classes(java_dir):
    """`dir/Name` (the dex spelling, without the `L` and `;`) of every class, object and interface the debug source set declares."""
    names = set()
    for path in sorted(pathlib.Path(java_dir).rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not package:
            continue
        for m in re.finditer(r"^(?:(?:internal|private|public|data|sealed|abstract|open|enum|fun)\s+)*(?:class|object|interface)\s+(\w+)", text, re.M):
            names.add(package.group(1).replace(".", "/") + "/" + m.group(1))
    return sorted(names)

def crash_texts(res_dir):
    """The three languages' title and subtitle of the test crash row, read from the resources so they cannot drift."""
    found = []
    for values in sorted(pathlib.Path(res_dir).glob("values*/strings_settings.xml")):
        for name in ("setcat_trigger_crash_title", "setcat_trigger_crash_subtitle"):
            m = re.search(r'<string name="%s"[^>]*>(.*?)</string>' % name, values.read_text(encoding="utf-8"), re.S)
            if m:
                found.append(m.group(1).replace("\\'", "'").replace("&amp;", "&"))
    return found

def needles(debug_manifest, res_dir, debug_java=None):
    java = pathlib.Path(debug_java) if debug_java else pathlib.Path(debug_manifest).parent / "java"
    classes = sorted(set(DEBUG_CLASSES) | (set(debug_source_classes(java)) if java.is_dir() else set()))
    result = [("class", name) for name in classes]
    result += [("action", action) for action in debug_actions(debug_manifest)]
    result += [("row text", text) for text in DEBUG_TEXT]
    result += [("development instrumentation", text) for text in DEBUG_INSTRUMENTATION]
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

def missing_required(path, required=REQUIRED):
    """[(kind, needle)] of every required thing the archive's code and resources do not carry (B39-S2)."""
    found = set()
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not is_inspected(info.filename):
                continue
            data = archive.read(info)
            for kind, needle in required:
                if needle.encode("utf-8") in data or needle.encode("utf-16-le") in data:
                    found.add((kind, needle))
    return [item for item in required if item not in found]

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

def native_abis(path):
    with zipfile.ZipFile(path) as archive:
        return sorted({match.group(1) for name in archive.namelist()
                       if (match := re.match(r"^(?:[^/]+/)?lib/([^/]+)/[^/]+\.so$", name))})

def unexpected_native_abis(path):
    return sorted(set(native_abis(path)) - {"arm64-v8a", "armeabi-v7a"})

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
        retired = retired_producer_hits(path)
        for entry, needle in retired:
            print(f"  FAIL {path}: retired producer {needle} in {entry}")
        failed = failed or bool(retired)
        abis = native_abis(path)
        unexpected_abis = unexpected_native_abis(path)
        print(f"  native ABIs: {', '.join(abis) or 'none in fixture'}")
        for abi in unexpected_abis:
            print(f"  FAIL {path}: debug-only native ABI {abi} in release")
        failed = failed or bool(unexpected_abis)
        hits, inspected = scan(path, wanted)
        inspected_total += inspected
        print(f"== {path}: {inspected} files read, {len(wanted)} things looked for")
        for name, kind, needle in hits:
            print(f"  FAIL {name}: {kind} {needle!r}")
        failed = failed or bool(hits)
        if not hits:
            print("  ok   no debug class, action or row text, and no old product name")
        lost = missing_required(path)
        for kind, needle in lost:
            print(f"  FAIL {path}: {kind} {needle!r} is missing from the release")
        failed = failed or bool(lost)
        if not lost:
            print(f"  ok   the release has the noise mark ({len(REQUIRED)} things looked for)")
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
