#!/usr/bin/env python3
"""Reproducible PCM reference and USB-transfer manifest tooling.

Requires only Python plus local ffmpeg/ffprobe for decoding source media.
The USB expectation follows Decent's documented integer widening: signed
16-bit samples are shifted left 16 and packed 24-bit samples left 8 when the
endpoint uses 32-bit slots.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import random
import struct
import subprocess
import sys
import wave

BLOCK_BYTES = 1024 * 1024


def _hashes(data: bytes, block_bytes: int = BLOCK_BYTES) -> tuple[str, list[str]]:
    return (
        hashlib.sha256(data).hexdigest(),
        [hashlib.sha256(data[i:i + block_bytes]).hexdigest()
         for i in range(0, len(data), block_bytes)],
    )


def widen_samples(pcm: bytes, source_bits: int, slot_bits: int) -> bytes:
    if source_bits == slot_bits:
        return pcm
    if slot_bits != 32 or source_bits not in (16, 24):
        raise ValueError(f"unsupported transport mapping {source_bits}->{slot_bits}")
    width = source_bits // 8
    if len(pcm) % width:
        raise ValueError("canonical PCM is not sample aligned")
    output = bytearray((len(pcm) // width) * 4)
    shift = 32 - source_bits
    for index in range(0, len(pcm), width):
        value = int.from_bytes(pcm[index:index + width], "little", signed=True)
        struct.pack_into("<i", output, (index // width) * 4, value << shift)
    return bytes(output)


def decode_pcm(path: pathlib.Path, source_bits: int) -> bytes:
    codec = {16: "pcm_s16le", 24: "pcm_s24le", 32: "pcm_s32le"}[source_bits]
    muxer = {16: "s16le", 24: "s24le", 32: "s32le"}[source_bits]
    return subprocess.check_output([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path),
        "-map", "0:a:0", "-f", muxer, "-acodec", codec, "-",
    ])


def probe(path: pathlib.Path) -> dict:
    raw = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "a:0",
        "-show_entries", "stream=codec_name,sample_rate,channels,bits_per_sample,bits_per_raw_sample",
        "-of", "json", str(path),
    ])
    stream = json.loads(raw)["streams"][0]
    return {
        "codec": stream.get("codec_name"),
        "sample_rate_hz": int(stream["sample_rate"]),
        "channels": int(stream["channels"]),
        "reported_bits_per_sample": int(stream.get("bits_per_raw_sample") or stream.get("bits_per_sample") or 0),
    }


def analyze(path: pathlib.Path, source_bits: int, slot_bits: int) -> dict:
    metadata = probe(path)
    canonical = decode_pcm(path, source_bits)
    frame_bytes = (source_bits // 8) * metadata["channels"]
    if len(canonical) % frame_bytes:
        raise ValueError("decoded PCM is not frame aligned")
    usb = widen_samples(canonical, source_bits, slot_bits)
    canonical_sha, canonical_blocks = _hashes(canonical)
    usb_sha, usb_blocks = _hashes(usb)
    low_nonzero = 0
    samples = len(canonical) // (source_bits // 8)
    if source_bits == 24:
        low_nonzero = sum(canonical[i] != 0 for i in range(0, len(canonical), 3))
    return {
        "schema": 1,
        "source": str(path),
        "source_file_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        **metadata,
        "source_bits": source_bits,
        "canonical_format": f"s{source_bits}le",
        "canonical_pcm_bytes": len(canonical),
        "pcm_frames": len(canonical) // frame_bytes,
        "canonical_pcm_sha256": canonical_sha,
        "canonical_block_bytes": BLOCK_BYTES,
        "canonical_block_sha256": canonical_blocks,
        "low_8_nonzero_percent": round(100 * low_nonzero / samples, 6) if source_bits == 24 else None,
        "usb_slot_bits": slot_bits,
        "usb_mapping": "identity" if source_bits == slot_bits else f"signed_{source_bits}_shift_left_{32-source_bits}",
        "expected_usb_bytes": len(usb),
        "expected_usb_sha256": usb_sha,
        "expected_usb_block_bytes": BLOCK_BYTES,
        "expected_usb_block_sha256": usb_blocks,
    }


def _sample_pattern(bits: int, frame: int, channel: int, rng: random.Random) -> int:
    limits = [0, 1, -1, (1 << (bits - 1)) - 1, -(1 << (bits - 1)),
              0x55 if bits == 16 else 0x555555, -0x56 if bits == 16 else -0x555556]
    if frame < len(limits):
        value = limits[(frame + channel) % len(limits)]
    else:
        value = rng.randrange(-(1 << (bits - 1)), 1 << (bits - 1))
    if bits == 24 and frame >= len(limits):
        value = (value & ~0xff) | ((frame * 73 + channel * 151) & 0xff)
        if value >= (1 << 23):
            value -= 1 << 24
    return value


def generate_wav(path: pathlib.Path, bits: int, rate: int, seconds: int, seed: int) -> None:
    rng = random.Random(seed)
    width = bits // 8
    with wave.open(str(path), "wb") as output:
        output.setnchannels(2)
        output.setsampwidth(width)
        output.setframerate(rate)
        block = bytearray()
        for frame in range(rate * seconds):
            for channel in range(2):
                value = _sample_pattern(bits, frame, channel, rng)
                block.extend(value.to_bytes(width, "little", signed=True))
            if len(block) >= 256 * 1024:
                output.writeframesraw(block)
                block.clear()
        if block:
            output.writeframesraw(block)


def compare(reference: dict, capture: dict) -> dict:
    checks = {
        "sample_rate": capture.get("sample_rate_hz") == reference.get("sample_rate_hz"),
        "channels": capture.get("channels") == reference.get("channels"),
        "slot_bits": capture.get("slot_bits") == reference.get("usb_slot_bits"),
        "frame_count": capture.get("frames") == reference.get("pcm_frames"),
        "byte_count": capture.get("bytes") == reference.get("expected_usb_bytes"),
        "requested_byte_count": capture.get("expected_bytes") == reference.get("expected_usb_bytes"),
        "capture_target_reached": capture.get("target_reached") is True,
        "payload_sha256": capture.get("sha256") == reference.get("expected_usb_sha256"),
        "block_sha256": capture.get("block_sha256") == reference.get("expected_usb_block_sha256"),
        "single_format": capture.get("format_changed") is False,
    }
    passed = all(checks.values())
    if passed and reference["source_bits"] == reference["usb_slot_bits"]:
        verdict = "USB OUT PAYLOAD MATCH VERIFIED (BYTE-IDENTICAL PCM LAYOUT)"
    elif passed:
        verdict = "USB OUT PAYLOAD MATCH VERIFIED (SIGNIFICANT-BIT PERFECT MAPPING)"
    else:
        verdict = "NOT VERIFIED"
    return {"passed": passed, "verdict": verdict, "checks": checks}


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("generate")
    gen.add_argument("output_dir", type=pathlib.Path)
    gen.add_argument("--seconds", type=int, default=12)
    ana = sub.add_parser("analyze")
    ana.add_argument("source", type=pathlib.Path)
    ana.add_argument("--source-bits", type=int, required=True, choices=(16, 24, 32))
    ana.add_argument("--usb-slot-bits", type=int, default=32, choices=(16, 24, 32))
    ana.add_argument("--output", type=pathlib.Path)
    cmp_parser = sub.add_parser("compare")
    cmp_parser.add_argument("reference", type=pathlib.Path)
    cmp_parser.add_argument("capture", type=pathlib.Path)
    cmp_parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()

    if args.command == "generate":
        args.output_dir.mkdir(parents=True, exist_ok=True)
        matrix = [(16, 44100), (24, 48000), (24, 96000), (24, 192000)]
        manifests = []
        for bits, rate in matrix:
            wav = args.output_dir / f"bp-vector-{bits}bit-{rate}hz.wav"
            generate_wav(wav, bits, rate, args.seconds, 0xD5_84_2026 + bits + rate)
            manifest = analyze(wav, bits, 32)
            out = wav.with_suffix(".reference.json")
            out.write_text(json.dumps(manifest, indent=2) + "\n")
            manifests.append(str(out))
        print(json.dumps({"generated": manifests}, indent=2))
        return 0

    if args.command == "analyze":
        result = analyze(args.source, args.source_bits, args.usb_slot_bits)
    else:
        result = compare(json.loads(args.reference.read_text()), json.loads(args.capture.read_text()))
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered)
    print(rendered, end="")
    return 0 if result.get("passed", True) else 1


if __name__ == "__main__":
    raise SystemExit(main())
