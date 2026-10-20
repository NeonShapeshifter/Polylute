import hashlib
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from bitperfect_reference import BLOCK_BYTES, compare, widen_samples


class BitPerfectReferenceTest(unittest.TestCase):
    def test_16_to_32_preserves_signed_samples(self):
        source = b"\x00\x80\xff\x7f\x01\x00\xff\xff"
        widened = widen_samples(source, 16, 32)
        values = [int.from_bytes(widened[i:i+4], "little", signed=True) >> 16
                  for i in range(0, len(widened), 4)]
        self.assertEqual([-32768, 32767, 1, -1], values)

    def test_24_to_32_preserves_low_bits_and_negative_values(self):
        samples = [-8388608, -1, 0, 1, 0x123456, 8388607]
        source = b"".join(value.to_bytes(3, "little", signed=True) for value in samples)
        widened = widen_samples(source, 24, 32)
        recovered = [int.from_bytes(widened[i:i+4], "little", signed=True) >> 8
                     for i in range(0, len(widened), 4)]
        self.assertEqual(samples, recovered)
        self.assertTrue(all(widened[i] == 0 for i in range(0, len(widened), 4)))

    def _manifests(self, payload: bytes, source_bits=24):
        capture = {
            "sample_rate_hz": 96000, "channels": 2, "slot_bits": 32,
            "frames": len(payload) // 8, "bytes": len(payload),
            "expected_bytes": len(payload), "target_reached": True,
            "sha256": hashlib.sha256(payload).hexdigest(),
            "block_sha256": [hashlib.sha256(payload[i:i+BLOCK_BYTES]).hexdigest()
                              for i in range(0, len(payload), BLOCK_BYTES)],
            "format_changed": False,
        }
        reference = {
            "sample_rate_hz": 96000, "channels": 2, "source_bits": source_bits,
            "usb_slot_bits": 32, "pcm_frames": len(payload) // 8,
            "expected_usb_bytes": len(payload),
            "expected_usb_sha256": capture["sha256"],
            "expected_usb_block_sha256": capture["block_sha256"],
        }
        return reference, capture

    def test_identical_chunked_payload_passes(self):
        payload = bytes(range(256)) * 9000
        reference, capture = self._manifests(payload)
        result = compare(reference, capture)
        self.assertTrue(result["passed"])
        self.assertIn("SIGNIFICANT-BIT", result["verdict"])

    def test_changed_low_order_bit_fails(self):
        payload = bytearray(bytes(range(256)) * 50)
        reference, capture = self._manifests(bytes(payload))
        payload[8] ^= 0x01
        capture["sha256"] = hashlib.sha256(payload).hexdigest()
        self.assertFalse(compare(reference, capture)["passed"])

    def test_dropped_or_duplicated_frame_fails(self):
        payload = bytes(range(64)) * 20
        for changed in (payload[8:], payload[:8] + payload):
            reference, capture = self._manifests(payload)
            capture["bytes"] = len(changed)
            capture["frames"] = len(changed) // 8
            capture["sha256"] = hashlib.sha256(changed).hexdigest()
            self.assertFalse(compare(reference, capture)["passed"])

    def test_incomplete_capture_target_fails_even_when_prefix_metadata_matches(self):
        payload = bytes(range(64)) * 20
        reference, capture = self._manifests(payload)
        capture["target_reached"] = False
        result = compare(reference, capture)
        self.assertFalse(result["passed"])
        self.assertFalse(result["checks"]["capture_target_reached"])

    def test_channel_swap_and_24_to_16_truncation_fail(self):
        left = (0x123456 << 8).to_bytes(4, "little", signed=True)
        right = (-0x234567 << 8).to_bytes(4, "little", signed=True)
        payload = (left + right) * 20
        reference, capture = self._manifests(payload)
        swapped = (right + left) * 20
        capture["sha256"] = hashlib.sha256(swapped).hexdigest()
        self.assertFalse(compare(reference, capture)["passed"])
        truncated = b"".join(chunk[:3][:2] + b"\x00\x00" for chunk in
                             [payload[i:i+4] for i in range(0, len(payload), 4)])
        capture["sha256"] = hashlib.sha256(truncated).hexdigest()
        self.assertFalse(compare(reference, capture)["passed"])

    def test_unexplained_pre_roll_fails_but_documented_reference_can_include_it(self):
        payload = bytes(range(80))
        reference, capture = self._manifests(payload)
        preroll = b"\x00" * 8 + payload
        capture["bytes"] = len(preroll)
        capture["frames"] = len(preroll) // 8
        capture["sha256"] = hashlib.sha256(preroll).hexdigest()
        self.assertFalse(compare(reference, capture)["passed"])

    def test_same_slot_layout_is_byte_identity(self):
        payload = bytes(range(64))
        reference, capture = self._manifests(payload, source_bits=32)
        result = compare(reference, capture)
        self.assertTrue(result["passed"])
        self.assertIn("BYTE-IDENTICAL", result["verdict"])


if __name__ == "__main__":
    unittest.main()
