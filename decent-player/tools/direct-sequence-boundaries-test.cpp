#include "usb-pcm-packetizer.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>

static void expectPacketBytes(const UsbPcmUrbPlan &plan, int expected) {
    int sum = 0;
    for (int i = 0; i < plan.packetCount; i++) {
        assert(plan.packetSizes[i] > 0);
        sum += plan.packetSizes[i];
    }
    assert(sum == expected);
    assert(plan.totalBytes == expected);
}

int main() {
    constexpr int frameBytes = 8;  // stereo in 32-bit USB slots
    constexpr int maxPacket = 1024;
    constexpr double sixFramesPerMicroframe = 6.0;

    // Eight complete packets: normal streaming submits exactly one full URB.
    auto full = planUsbPcmUrb(384, frameBytes, maxPacket,
                              sixFramesPerMicroframe, 0.0, false);
    assert(full.packetCount == 8);
    expectPacketBytes(full, 384);

    // A normal short write is held without consuming packet-clock state.
    auto held = planUsbPcmUrb(frameBytes, frameBytes, maxPacket,
                              sixFramesPerMicroframe, 0.25, false);
    assert(held.packetCount == 0);
    assert(held.totalBytes == 0);
    assert(held.nextFrameAccumulator == 0.25);

    // Natural EOS transmits a one-frame residual once, with no padding.
    auto oneFrame = planUsbPcmUrb(frameBytes, frameBytes, maxPacket,
                                  sixFramesPerMicroframe, 0.25, true);
    assert(oneFrame.packetCount == 1);
    assert(oneFrame.consumesAllAvailable);
    expectPacketBytes(oneFrame, frameBytes);

    // A multi-frame short residual is also exact and frame aligned.
    auto fiveFrames = planUsbPcmUrb(5 * frameBytes, frameBytes, maxPacket,
                                    sixFramesPerMicroframe, 0.0, true);
    assert(fiveFrames.packetCount == 1);
    assert(fiveFrames.consumesAllAvailable);
    expectPacketBytes(fiveFrames, 5 * frameBytes);

    // Exact-full-buffer EOS does not need or invent an extra tail.
    auto fullAtEos = planUsbPcmUrb(384, frameBytes, maxPacket,
                                   sixFramesPerMicroframe, 0.0, true);
    assert(fullAtEos.packetCount == 8);
    expectPacketBytes(fullAtEos, 384);

    // AUD-006 (probe-packetizer-maxpacket.cpp): the plan is only as good as the maxPacket it is
    // given. The DS2's first AS alt (16-bit) has maxPacket 200; the 32-bit alt actually streamed
    // has 400. 352.8/384 kHz stereo 32-bit needs up to 360/384 bytes per microframe, so the first
    // alt's maxPacket produced an empty plan (silence) and the selected alt's carries every rate.
    {
        const int rates[] = {44100, 96000, 176400, 192000, 352800, 384000};
        for (int rate : rates) {
            const double fpmf = rate / 8000.0;
            auto selected = planUsbPcmUrb(8 * 1024 * 8, frameBytes, 400, fpmf, 0.0, false);
            assert(selected.packetCount == 8);
            for (int i = 0; i < selected.packetCount; i++) assert(selected.packetSizes[i] <= 400);
            auto firstAlt = planUsbPcmUrb(8 * 1024 * 8, frameBytes, 200, fpmf, 0.0, false);
            if (rate >= 352800) assert(firstAlt.packetCount == 0);
        }
    }

    // AUD-012: mono is upmixed to the alt's two channels before it reaches the packetizer, so a
    // 44.1 kHz URB carries 8-byte frames (352-360 bytes per URB), not 4-byte ones.
    {
        auto upmixed = planUsbPcmUrb(4096, frameBytes, 400, 44100 / 8000.0, 0.0, false);
        assert(upmixed.packetCount == 8);
        assert(upmixed.totalBytes >= 44 * frameBytes && upmixed.totalBytes <= 45 * frameBytes);
    }

    // A partial stereo frame is rejected rather than zero padded.
    auto partialFrame = planUsbPcmUrb(frameBytes - 1, frameBytes, maxPacket,
                                      sixFramesPerMicroframe, 0.0, true);
    assert(partialFrame.packetCount == 0);
    assert(partialFrame.totalBytes == 0);
    assert(!partialFrame.consumesAllAvailable);

    // 16-bit source widened to 32-bit and packed 24-in-32 have the same
    // endpoint frame alignment; planning never touches sample/channel bytes.
    unsigned char stereoFrame[frameBytes] = {0x11, 0x22, 0x33, 0x44,
                                              0x55, 0x66, 0x77, 0x88};
    unsigned char before[frameBytes];
    memcpy(before, stereoFrame, frameBytes);
    auto aligned = planUsbPcmUrb(frameBytes, frameBytes, maxPacket,
                                 sixFramesPerMicroframe, 0.0, true);
    expectPacketBytes(aligned, frameBytes);
    assert(memcmp(before, stereoFrame, frameBytes) == 0);

    // Impossible endpoint packet capacity fails without truncation.
    auto tooLarge = planUsbPcmUrb(384, frameBytes, 40,
                                  sixFramesPerMicroframe, 0.0, false);
    assert(tooLarge.packetCount == 0);
    assert(tooLarge.totalBytes == 0);

    // Audit 2, minor #1: the URB slot must hold the largest URB the plan can produce. 768 kHz stereo in
    // 32-bit slots is 96 frames (768 bytes) per microframe, 6144 bytes per URB — past the 4096-byte
    // slot every ring buffer used to get, so the copy overran the heap into the DAC's stream.
    auto hiRes = planUsbPcmUrb(8 * 768, frameBytes, maxPacket, 96.0, 0.0, false);
    assert(hiRes.packetCount == 8);
    expectPacketBytes(hiRes, 6144);
    assert(hiRes.totalBytes <= usbUrbSlotBytes(maxPacket));
    // Whatever the endpoint allows (high-bandwidth: up to 3 x 1024 per microframe) fits its slot.
    const int maxPackets[] = {200, 400, 1024, 2048, 3072};
    for (int packet : maxPackets) {
        const int frames = packet / frameBytes;
        auto widest = planUsbPcmUrb(8 * frames * frameBytes, frameBytes, packet, frames, 0.0, false);
        assert(widest.packetCount == 8);
        assert(widest.totalBytes <= usbUrbSlotBytes(packet));
    }
    // Small endpoints keep the historical slot, so nothing changes at 44.1-384 kHz.
    assert(usbUrbSlotBytes(400) == 4096);

    printf("direct sequence boundary tests: PASS\n");
    return 0;
}
