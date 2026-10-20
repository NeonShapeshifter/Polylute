#!/usr/bin/env python3
"""16 KB page-size compatibility of the native libraries in an APK, an AAB, or loose .so files (B14-5, audit §6.4).

For every 64-bit library (arm64-v8a, x86_64: the ABIs Android ships 16 KB-page devices on):

  * every PT_LOAD segment is aligned to at least 16384 bytes, and
  * no writable data other than the RELRO itself lies in the pages the dynamic linker protects: it makes
    [page_start(RELRO start), page_end(RELRO end)) read-only after relocation, with the runtime page size, so a RELRO
    that ends mid-page also protects whatever else is in the rest of that 16 KB page (the Android page-size guide's
    non-compliant example is exactly writable data sharing it). B35 (RT-04): the RELRO end alone is not the criterion.
    A RELRO that ends at 0x6000 inside its own PT_LOAD, with the next writable data at 0x9ed8, protects nothing it
    should not, and the checker used to reject it (`libandroidx.graphics.path.so`, `libtaglib.so`, `libffmpegJNI.so`);

and, in an APK, every uncompressed .so starts at a multiple of 16384 in the file (what `zipalign -P 16` produces,
so that it can be mapped straight from the APK). The 32-bit ABIs are reported but never fail the check: the
requirement is for 64-bit code.

    check_page_size.py app-arm64-v8a-release-unsigned.apk app-release.aab
    check_page_size.py --zipalign ~/Android/Sdk/build-tools/37.0.0/zipalign some.apk   # also runs zipalign -c -P 16 4

Exit status 0 when everything passes, 1 when something fails, 2 when there was nothing to check.
"""
import argparse
import collections
import io
import os
import struct
import subprocess
import sys
import zipfile

PAGE = 16384
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2
CHECKED_ABIS = ("arm64-v8a", "x86_64")

Segment = collections.namedtuple("Segment", "type flags vaddr memsz align")


def elf_segments(data):
    """A Segment (type, flags, vaddr, memsz, align) for each program header of an ELF64 little-endian image, or None."""
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return None
    (phoff,) = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    segments = []
    for index in range(phnum):
        base = phoff + index * phentsize
        p_type, flags, _offset, vaddr, _paddr, _filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, base)
        segments.append(Segment(p_type, flags, vaddr, memsz, align))
    return segments


def protected_range(relro):
    """The pages the linker makes read-only for [relro]: page_start(start) up to page_end(end), at PAGE bytes."""
    start, end = relro.vaddr, relro.vaddr + relro.memsz
    return start - start % PAGE, end + (-end) % PAGE


def writable_in_protected_range(segments, relro):
    """[(start, end, load)]: writable memory of a PT_LOAD, other than the RELRO itself, inside the pages protected for [relro]."""
    start, end = relro.vaddr, relro.vaddr + relro.memsz
    low, high = protected_range(relro)
    found = []
    for load in segments:
        if load.type != PT_LOAD or not load.flags & PF_W:
            continue
        first, last = load.vaddr, load.vaddr + load.memsz
        # the RELRO is the part of the writable image that is meant to become read-only: what is left of the segment is data
        for lo, hi in ((first, min(last, start)), (max(first, end), last)):
            if lo < hi and lo < high and hi > low:
                found.append((max(lo, low), min(hi, high), load))
    return found


def analyze(data):
    """(problems, notes) of one ELF image, or None when it is not a 64-bit ELF. A note says what is unusual and safe."""
    segments = elf_segments(data)
    if segments is None:
        return None
    problems = []
    notes = []
    for segment in segments:
        if segment.type == PT_LOAD and segment.align < PAGE:
            problems.append(f"PT_LOAD p_align {segment.align} < {PAGE}")
        if segment.type != PT_GNU_RELRO or segment.memsz == 0:
            continue
        end = segment.vaddr + segment.memsz
        low, high = protected_range(segment)
        conflicts = writable_in_protected_range(segments, segment)
        for lo, hi, load in conflicts:
            problems.append(
                f"writable data {lo:#x}-{hi:#x} (PT_LOAD {load.vaddr:#x}-{load.vaddr + load.memsz:#x}) lies in the {PAGE}-byte pages "
                f"{low:#x}-{high:#x} that the linker makes read-only for the RELRO {segment.vaddr:#x}-{end:#x}"
            )
        if end % PAGE != 0 and not conflicts:
            notes.append(f"RELRO ends at {end:#x} ({end % PAGE:#x} past a {PAGE}-byte boundary) but nothing writable shares {end:#x}-{high:#x}")
    return problems, notes


def check_library(data):
    """List of problems (empty: compatible) of one ELF image; None when it is not a 64-bit ELF."""
    analysed = analyze(data)
    return None if analysed is None else analysed[0]


def abi_of(name):
    parts = name.split("/")
    if "lib" in parts:
        i = parts.index("lib")
        if i + 1 < len(parts) - 1:
            return parts[i + 1]
    return None


def zip_data_offset(archive, info):
    """Where the entry's bytes start in the file."""
    archive.fp.seek(info.header_offset)
    header = archive.fp.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_archive(path, check_zip_alignment):
    results = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if not info.filename.endswith(".so"):
                continue
            abi = abi_of(info.filename)
            if abi is None:
                continue
            analysed = analyze(archive.read(info))
            if analysed is None:
                results.append((info.filename, abi, ["not an ELF64 image"], False, []))
                continue
            problems, notes = analysed
            if check_zip_alignment and abi in CHECKED_ABIS:
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append("stored compressed: it has to be uncompressed to be aligned in the APK")
                elif zip_data_offset(archive, info) % PAGE != 0:
                    problems.append(f"ZIP data offset {zip_data_offset(archive, info):#x} is not {PAGE}-aligned")
            results.append((info.filename, abi, problems, abi in CHECKED_ABIS, notes))
    return results


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="+")
    parser.add_argument("--zipalign", help="path to zipalign (build-tools 35+): also run `zipalign -c -P 16 4` on each APK")
    args = parser.parse_args(argv)

    failed = False
    checked = 0
    for path in args.files:
        print(f"== {path}")
        if path.endswith(".so"):
            with open(path, "rb") as handle:
                analysed = analyze(handle.read())
            problems, notes = analysed if analysed is not None else (["not an ELF64 image"], [])
            rows = [(os.path.basename(path), "?", problems, True, notes)]
        else:
            is_apk = path.endswith(".apk")
            rows = check_archive(path, check_zip_alignment=is_apk)
        for name, abi, problems, enforced, notes in rows:
            if enforced:
                checked += 1
            status = "ok" if not problems else ("FAIL" if enforced else "info")
            print(f"  {status:4} {abi:12} {name}" + ("" if not problems else "  <- " + "; ".join(problems)))
            for note in notes:
                print(f"       note: {note}")
            if problems and enforced:
                failed = True
        if args.zipalign and path.endswith(".apk"):
            result = subprocess.run([args.zipalign, "-c", "-P", "16", "4", path], capture_output=True, text=True)
            print(f"  zipalign -c -P 16 4: {'ok' if result.returncode == 0 else 'FAIL'} {result.stdout.strip()}{result.stderr.strip()}")
            failed = failed or result.returncode != 0
    if checked == 0:
        print("nothing to check: no 64-bit native library found", file=sys.stderr)
        return 2
    print("16 KB check:", "FAILED" if failed else "passed", f"({checked} 64-bit libraries)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
