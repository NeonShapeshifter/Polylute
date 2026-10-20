import struct
import unittest

import check_page_size as check


def elf64(segments):
    """A minimal little-endian ELF64 image with the given (p_type, p_vaddr, p_memsz, p_align[, p_flags]) program headers."""
    ehdr = bytearray(64)
    ehdr[:4] = b"\x7fELF"
    ehdr[4] = 2
    ehdr[5] = 1
    struct.pack_into("<Q", ehdr, 0x20, 64)           # e_phoff
    struct.pack_into("<HH", ehdr, 0x36, 56, len(segments))  # e_phentsize, e_phnum
    body = b"".join(
        struct.pack("<IIQQQQQQ", segment[0], segment[4] if len(segment) > 4 else 0, 0, segment[1], segment[1], segment[2], segment[2], segment[3])
        for segment in segments
    )
    return bytes(ehdr) + body


LOAD, RELRO = check.PT_LOAD, check.PT_GNU_RELRO
R, RW, RX = 4, 6, 5   # p_flags of a read-only, a writable and an executable segment


def load(start, end, flags, align=0x4000):
    return (LOAD, start, end - start, align, flags)


def relro(start, end):
    return (RELRO, start, end - start, 1, 4)


class CheckPageSize(unittest.TestCase):
    def test_a_compliant_library_has_no_problem(self):
        image = elf64([load(0, 0x10000, RX), load(0x10000, 0x18000, RW), relro(0x10000, 0x14000)])
        self.assertEqual(check.check_library(image), [])

    def test_a_relro_that_ends_mid_page_with_writable_data_in_the_rest_of_the_page_fails(self):
        # The dangerous case, and the one the Android guide shows: one writable segment holds the RELRO (0x7c000-0x86000) and the data
        # that follows it, so the linker's mprotect of the page-rounded RELRO (up to 0x88000) would make that data read-only.
        image = elf64([load(0, 0x7c000, RX), load(0x7c000, 0x88000, RW), relro(0x7c000, 0x86000)])
        problems = check.check_library(image)
        self.assertEqual(len(problems), 1)
        self.assertIn("writable data 0x86000-0x88000", problems[0])
        self.assertIn("RELRO 0x7c000-0x86000", problems[0])

    def test_the_guides_own_example(self):
        # (0xdfaf0 + 0x01510) % 0x4000 == 0x1000 is the guide's non-compliant example: a writable segment keeps going after the RELRO.
        image = elf64([load(0, 0xd0000, RX), load(0xd0000, 0x100000, RW), relro(0xDFAF0, 0xDFAF0 + 0x1510)])
        problems = check.check_library(image)
        self.assertEqual(len(problems), 2)             # data before the RELRO in its first page, and data after it in the rest of its last
        self.assertIn("writable data 0xdc000-0xdfaf0", problems[0])
        self.assertIn("writable data 0xe1000-0xe4000", problems[1])

    def test_writable_data_that_starts_inside_the_rounded_tail_fails(self):
        # RELRO ends at 0x6000; the page-rounded end is 0x8000; a second writable segment starts at 0x7000.
        image = elf64([load(0, 0x1b40, RX), load(0x5b40, 0x6000, RW), load(0x7000, 0x7100, RW), relro(0x5b40, 0x6000)])
        problems = check.check_library(image)
        self.assertEqual(len(problems), 1)
        self.assertIn("writable data 0x7000-0x7100", problems[0])

    def test_writable_data_that_starts_exactly_at_the_rounded_end_is_outside_it(self):
        image = elf64([load(0, 0x1b40, RX), load(0x5b40, 0x6000, RW), load(0x8000, 0x8100, RW), relro(0x5b40, 0x6000)])
        self.assertEqual(check.check_library(image), [])

    def test_writable_data_one_byte_inside_the_rounded_end_fails(self):
        image = elf64([load(0, 0x1b40, RX), load(0x5b40, 0x6000, RW), load(0x7fff, 0x8100, RW), relro(0x5b40, 0x6000)])
        self.assertEqual(len(check.check_library(image)), 1)

    def test_writable_data_in_the_page_before_the_relro_starts_fails_too(self):
        # the linker rounds the start down as well: 0x5b40 -> 0x4000, and a writable segment ending at 0x5000 is in that page
        image = elf64([load(0, 0x1b40, RX), load(0x4000, 0x5000, RW), load(0x5b40, 0x6000, RW), relro(0x5b40, 0x6000)])
        problems = check.check_library(image)
        self.assertEqual(len(problems), 1)
        self.assertIn("writable data 0x4000-0x5000", problems[0])

    def test_read_only_and_executable_segments_in_the_tail_are_not_judged_writable(self):
        image = elf64([load(0, 0x1b40, RX), load(0x5b40, 0x6000, RW), load(0x7000, 0x7100, R), relro(0x5b40, 0x6000)])
        self.assertEqual(check.check_library(image), [])

    def test_a_relro_that_ends_on_a_page_boundary_leaves_no_tail_even_with_data_after_it(self):
        image = elf64([load(0, 0x1b40, RX), load(0x5b40, 0x8000, RW), load(0x8000, 0x8100, RW), relro(0x5b40, 0x8000)])
        self.assertEqual(check.check_library(image), [])

    def test_the_relro_itself_is_not_the_writable_data_it_protects(self):
        # a RELRO that is the whole writable segment, unaligned end, nothing else writable near: compliant
        image = elf64([load(0, 0x1b40, RX), load(0x5b40, 0x6000, RW), relro(0x5b40, 0x6000)])
        analysed = check.analyze(image)
        self.assertEqual(analysed[0], [])
        self.assertEqual(len(analysed[1]), 1)         # said, as a note, not as a failure

    def test_a_four_kb_load_segment_fails(self):
        image = elf64([(LOAD, 0, 0x10000, 0x1000)])
        self.assertIn("p_align", check.check_library(image)[0])

    def test_the_load_alignment_still_fails_when_the_relro_is_fine(self):
        image = elf64([load(0, 0x1b40, RX, align=0x1000), load(0x5b40, 0x6000, RW), relro(0x5b40, 0x6000)])
        problems = check.check_library(image)
        self.assertEqual(len(problems), 1)
        self.assertIn("p_align", problems[0])

    def test_no_relro_at_all_is_trivially_compliant(self):
        self.assertEqual(check.check_library(elf64([(LOAD, 0, 0x10000, 0x4000)])), [])

    def test_a_32_bit_or_non_elf_image_is_not_judged(self):
        self.assertIsNone(check.check_library(b"not an elf"))
        thirty_two = bytearray(elf64([(LOAD, 0, 0x10000, 0x4000)]))
        thirty_two[4] = 1
        self.assertIsNone(check.check_library(bytes(thirty_two)))

    def test_abi_is_read_from_the_path_in_an_apk_and_in_an_aab(self):
        self.assertEqual(check.abi_of("lib/arm64-v8a/libx.so"), "arm64-v8a")
        self.assertEqual(check.abi_of("base/lib/x86_64/libx.so"), "x86_64")
        self.assertIsNone(check.abi_of("assets/libx.so"))


class RealPrebuiltLayouts(unittest.TestCase):
    """B35 (RT-04): the program headers of the prebuilt libraries the checker used to reject, read from the release AAB of 2026-10-20.

    Each has its RELRO in a PT_LOAD of its own that ends mid-page, and the next writable segment starts beyond the page-rounded end:
    the linker's mprotect cannot reach any data. Only the headers are reproduced (type, flags, vaddr, memsz, align), no code.
    """

    # (the library, its PT_LOADs as (start, end, flags), its RELRO as (start, end)); all with p_align 0x4000
    ARM64 = {
        "libandroidx.graphics.path.so (androidx.graphics:graphics-path)": ([(0x0, 0x1B40, RX), (0x5B40, 0x6000, RW), (0x9ED8, 0x9EE8, RW)], (0x5B40, 0x6000)),
        "libtaglib.so (io.github.kyant0:taglib:1.0.6)": ([(0x0, 0x128D60, RX), (0x12CD60, 0x136000, RW), (0x139BF8, 0x13BE08, RW)], (0x12CD60, 0x136000)),
        "libffmpegJNI.so (org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1)": ([(0x0, 0x145BE0, RX), (0x149BE0, 0x168670, RW), (0x16C670, 0x1295148, RW)], (0x149BE0, 0x169000)),
    }
    X86_64 = {
        "libandroidx.graphics.path.so": ([(0x0, 0x1BC0, RX), (0x5BC0, 0x6000, RW), (0x9F70, 0x9F80, RW)], (0x5BC0, 0x6000)),
        "libtaglib.so": ([(0x0, 0x12BF30, RX), (0x12FF30, 0x139000, RW), (0x13CCF0, 0x13EEF0, RW)], (0x12FF30, 0x139000)),
    }

    def image(self, loads, relro_range):
        return elf64([load(start, end, flags) for start, end, flags in loads] + [relro(*relro_range)])

    def test_the_three_arm64_prebuilts_are_compatible(self):
        for name, (loads, relro_range) in self.ARM64.items():
            with self.subTest(library=name):
                analysed = check.analyze(self.image(loads, relro_range))
                self.assertEqual(analysed[0], [])
                self.assertEqual(len(analysed[1]), 1)           # the unaligned RELRO end is reported, with the free tail it leaves

    def test_the_two_x86_64_prebuilts_are_compatible(self):
        for name, (loads, relro_range) in self.X86_64.items():
            with self.subTest(library=name):
                self.assertEqual(check.check_library(self.image(loads, relro_range)), [])

    def test_the_same_layouts_with_one_writable_segment_pulled_into_the_tail_are_rejected(self):
        # the control for each real layout: the next writable segment moved to start inside the page-rounded end
        for name, (loads, relro_range) in self.ARM64.items():
            with self.subTest(library=name):
                low, high = check.protected_range(check.Segment(RELRO, 4, relro_range[0], relro_range[1] - relro_range[0], 1))
                tail = loads[:2] + [(relro_range[1] + 0x10, relro_range[1] + 0x20, RW)]
                self.assertLess(relro_range[1] + 0x20, high + 1)
                self.assertEqual(len(check.check_library(self.image(tail, relro_range))), 1)


if __name__ == "__main__":
    unittest.main()
