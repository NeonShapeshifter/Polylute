#include "usb-pcm-packetizer.h"

UsbPcmUrbPlan planUsbPcmUrb(int availableBytes,
                            int bytesPerFrame,
                            int maxPacketSize,
                            double framesPerMicroframe,
                            double frameAccumulator,
                            bool naturalEndOfStream) {
    UsbPcmUrbPlan plan = {};
    plan.nextFrameAccumulator = frameAccumulator;

    if (availableBytes <= 0 || bytesPerFrame <= 0 || maxPacketSize <= 0 ||
        framesPerMicroframe <= 0.0) {
        return plan;
    }

    if (naturalEndOfStream && availableBytes % bytesPerFrame != 0) {
        // A partial PCM frame is not valid audio and must never be padded.
        return plan;
    }

    int remaining = availableBytes;
    double candidateAccumulator = frameAccumulator;
    for (int packet = 0; packet < USB_PCM_PACKETS_PER_URB && remaining > 0; packet++) {
        candidateAccumulator += framesPerMicroframe;
        int scheduledFrames = static_cast<int>(candidateAccumulator);
        if (scheduledFrames <= 0) return UsbPcmUrbPlan{};
        candidateAccumulator -= scheduledFrames;

        int scheduledBytes = scheduledFrames * bytesPerFrame;
        int packetBytes = scheduledBytes;
        if (packetBytes > remaining) {
            if (!naturalEndOfStream) break;
            packetBytes = remaining;
        }

        if (packetBytes <= 0 || packetBytes > maxPacketSize ||
            packetBytes % bytesPerFrame != 0) {
            return UsbPcmUrbPlan{};
        }

        plan.packetSizes[plan.packetCount++] = packetBytes;
        plan.totalBytes += packetBytes;
        remaining -= packetBytes;
    }

    plan.consumesAllAvailable = remaining == 0;
    if (!naturalEndOfStream && plan.packetCount != USB_PCM_PACKETS_PER_URB) {
        // Do not consume fractional scheduling state for an URB we did not
        // submit. The same bytes and accumulator will be reconsidered when the
        // next source buffer is appended.
        plan.packetCount = 0;
        plan.totalBytes = 0;
        plan.consumesAllAvailable = false;
        plan.nextFrameAccumulator = frameAccumulator;
        return plan;
    }
    if (naturalEndOfStream && !plan.consumesAllAvailable) {
        return UsbPcmUrbPlan{};
    }

    plan.nextFrameAccumulator = candidateAccumulator;
    return plan;
}

int usbUrbSlotBytes(int maxPacketSize) {
    // 4096 covered 384 kHz stereo 32-bit (3072 B per URB); a plan above ~512 kHz reaches 8 x maxPacket.
    const int widest = USB_PCM_PACKETS_PER_URB * (maxPacketSize > 0 ? maxPacketSize : 0);
    return widest > USB_PCM_MIN_URB_SLOT_BYTES ? widest : USB_PCM_MIN_URB_SLOT_BYTES;
}
