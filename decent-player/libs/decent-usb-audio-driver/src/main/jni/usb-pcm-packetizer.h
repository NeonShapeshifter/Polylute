#pragma once

#define USB_PCM_PACKETS_PER_URB 8
/** The slot every ring buffer had before audit 2; still the size up to 384 kHz stereo 32-bit. */
#define USB_PCM_MIN_URB_SLOT_BYTES 4096
/** Largest URB a high-speed endpoint can take: 8 microframes x 3 x 1024 bytes. */
#define USB_PCM_MAX_URB_BYTES (USB_PCM_PACKETS_PER_URB * 3 * 1024)

/** Pure packet plan used by normal streaming and the natural-EOS drain. */
struct UsbPcmUrbPlan {
    int packetSizes[USB_PCM_PACKETS_PER_URB];
    int packetCount;
    int totalBytes;
    double nextFrameAccumulator;
    bool consumesAllAvailable;
};

/**
 * Plan one AudioStreaming URB without mutating stream state.
 *
 * Normal streaming returns a plan only when all eight microframe packets can
 * be filled. At natural EOS, a final short packet/URB may consume the remaining
 * complete source frames. No bytes are padded or invented.
 */
UsbPcmUrbPlan planUsbPcmUrb(int availableBytes,
                            int bytesPerFrame,
                            int maxPacketSize,
                            double framesPerMicroframe,
                            double frameAccumulator,
                            bool naturalEndOfStream);

/**
 * Bytes one URB slot of the ring must hold for an endpoint of [maxPacketSize]: a plan never exceeds
 * USB_PCM_PACKETS_PER_URB packets of maxPacketSize.
 */
int usbUrbSlotBytes(int maxPacketSize);
