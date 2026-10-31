#include "usb-pcm-packetizer.h"

#include <stdio.h>

#include "usb-pcm-convert.h"

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

// ── The transport plan (B46 phase A; see usb-pcm-packetizer.h) ──────────────────────────────────

UsbTransportPolicy usbTransportDefaultPolicy() {
    UsbTransportPolicy policy = {};
    policy.ringUrbs = USB_TRANSPORT_RING_URBS;
    policy.queueSoftMs = USB_TRANSPORT_QUEUE_SOFT_MS;
    policy.queueHardMs = USB_TRANSPORT_QUEUE_HARD_MS;
    policy.silenceLeadMs = USB_TRANSPORT_SILENCE_LEAD_MS;
    policy.resumeLeadMs = USB_TRANSPORT_RESUME_LEAD_MS;
    policy.feedbackStartGuardMs = USB_TRANSPORT_FEEDBACK_START_GUARD_MS;
    policy.pauseKeepaliveMs = USB_TRANSPORT_PAUSE_KEEPALIVE_MS;
    policy.fadeOutMs = USB_TRANSPORT_FADE_OUT_MS;
    policy.fadeInMs = USB_TRANSPORT_FADE_IN_MS;
    policy.prerollMs = 0;
    policy.prerollBlockFrames = USB_TRANSPORT_PREROLL_BLOCK_FRAMES;
    policy.feedbackCeilingPermille = USB_TRANSPORT_FEEDBACK_CEILING_PERMILLE;
    policy.refuseUnsupportedTiming = false;
    return policy;
}

static int64_t ceilDiv(int64_t numerator, int64_t denominator) {
    return (numerator + denominator - 1) / denominator;
}

static UsbTransportPlan unsupportedPlan(int reason) {
    UsbTransportPlan plan = {};
    plan.status = USB_PLAN_UNSUPPORTED;
    plan.reason = reason;
    return plan;
}

static bool policyIsValid(const UsbTransportPolicy &policy) {
    return policy.ringUrbs > 0 && policy.queueSoftMs > 0 && policy.queueHardMs >= policy.queueSoftMs &&
           policy.silenceLeadMs >= 0 && policy.resumeLeadMs >= 0 && policy.feedbackStartGuardMs >= 0 && policy.pauseKeepaliveMs >= 0 && policy.fadeOutMs >= 0 && policy.fadeInMs >= 0 && policy.prerollMs >= 0 &&
           policy.prerollBlockFrames > 0 && policy.feedbackCeilingPermille >= 0;
}

/** The timing the output does not drive yet, as USB_PLAN_ASSUME_* bits (0 when the device is what the output assumes). */
static int timingAssumptions(const UsbTransportEndpoint &endpoint) {
    int assumptions = 0;
    switch (endpoint.speed) {
        case USB_TRANSPORT_SPEED_LOW:
        case USB_TRANSPORT_SPEED_FULL:
        case USB_TRANSPORT_SPEED_WIRELESS:
            assumptions |= USB_PLAN_ASSUME_SERVICE_INTERVAL;
            break;
        case USB_TRANSPORT_SPEED_UNKNOWN:
            assumptions |= USB_PLAN_ASSUME_SPEED_UNKNOWN;
            break;
        default:
            break;
    }
    if (endpoint.bInterval > 1) assumptions |= USB_PLAN_ASSUME_BINTERVAL;
    if (endpoint.hasFeedback && endpoint.feedbackBytes > 0 && endpoint.feedbackBytes != USB_TRANSPORT_FEEDBACK_BYTES) {
        assumptions |= USB_PLAN_ASSUME_FEEDBACK_WIDTH;
    }
    return assumptions;
}

UsbTransportPlan makeUsbTransportPlan(const UsbTransportFormat &format,
                                      const UsbTransportEndpoint &endpoint,
                                      const UsbTransportPolicy &policy) {
    if (format.sampleRate <= 0 || format.channels <= 0 || format.containerBytes < 2 || format.containerBytes > 4 ||
        format.sourceBits < 0 || endpoint.maxPacketBytes <= 0 || !policyIsValid(policy)) {
        return unsupportedPlan(USB_PLAN_REASON_INVALID);
    }
    if (format.sourceBits != 0 && !usbPcmCanConvert(format.sourceBits, format.containerBytes * 8)) {
        return unsupportedPlan(USB_PLAN_REASON_CONTAINER_NARROWER);
    }
    if (policy.ringUrbs > USB_TRANSPORT_RING_URBS) return unsupportedPlan(USB_PLAN_REASON_RING_TOO_LARGE);

    const int assumptions = timingAssumptions(endpoint);
    if (policy.refuseUnsupportedTiming) {
        if (assumptions & USB_PLAN_ASSUME_SERVICE_INTERVAL) return unsupportedPlan(USB_PLAN_REASON_FULL_SPEED);
        if (assumptions & USB_PLAN_ASSUME_BINTERVAL) return unsupportedPlan(USB_PLAN_REASON_BINTERVAL);
        if (assumptions & USB_PLAN_ASSUME_FEEDBACK_WIDTH) return unsupportedPlan(USB_PLAN_REASON_FEEDBACK_WIDTH);
    }

    const int64_t bytesPerFrame = (int64_t)format.channels * format.containerBytes;
    const int64_t maxFrames = ceilDiv(format.sampleRate, USB_TRANSPORT_INTERVALS_PER_SECOND);
    const int64_t maxPacketBytes = maxFrames * bytesPerFrame;
    if (maxPacketBytes > endpoint.maxPacketBytes) return unsupportedPlan(USB_PLAN_REASON_PACKET_OVER_MAX);

    // With a feedback endpoint the DAC may ask for up to the ceiling above nominal; without one the rate is the nominal one.
    const int64_t ceilingPermille = endpoint.hasFeedback ? 1000 + policy.feedbackCeilingPermille : 1000;
    const int64_t maxFramesAtCeiling = ceilDiv((int64_t)format.sampleRate * ceilingPermille,
                                               (int64_t)USB_TRANSPORT_INTERVALS_PER_SECOND * 1000);

    UsbTransportPlan plan = {};
    plan.status = USB_PLAN_OK;
    plan.reason = USB_PLAN_REASON_NONE;
    plan.assumptions = assumptions;

    plan.serviceIntervalUs = USB_TRANSPORT_SERVICE_INTERVAL_US;
    plan.intervalsPerSecond = USB_TRANSPORT_INTERVALS_PER_SECOND;
    plan.nominalFramesNum = format.sampleRate;
    plan.nominalFramesDen = USB_TRANSPORT_INTERVALS_PER_SECOND;
    plan.nominalFramesPerInterval = format.sampleRate / (double)USB_TRANSPORT_INTERVALS_PER_SECOND;

    plan.bytesPerFrame = (int)bytesPerFrame;
    plan.maxFramesPerPacket = (int)maxFrames;
    plan.maxPacketBytes = (int)maxPacketBytes;
    plan.maxFramesPerPacketAtCeiling = (int)maxFramesAtCeiling;
    plan.maxPacketBytesAtCeiling = (int)(maxFramesAtCeiling * bytesPerFrame);
    plan.packetsPerUrb = USB_PCM_PACKETS_PER_URB;
    plan.urbMaxBytes = USB_PCM_PACKETS_PER_URB * endpoint.maxPacketBytes;
    plan.urbSlotBytes = usbUrbSlotBytes(endpoint.maxPacketBytes);
    plan.residualCapBytes = USB_PCM_MAX_URB_BYTES;

    plan.ringUrbs = policy.ringUrbs;
    plan.backpressureUrbs = policy.ringUrbs;
    plan.lowRingUrbs = policy.ringUrbs / 2;

    plan.queueSoftFrames = usbFramesForMs(format.sampleRate, policy.queueSoftMs);
    plan.queueHardFrames = usbFramesForMs(format.sampleRate, policy.queueHardMs);

    plan.silenceLeadFrames = (int)usbFramesForMs(format.sampleRate, policy.silenceLeadMs);
    plan.silenceChunkFrames = format.sampleRate / 1000 + 1;
    plan.resumeLeadFrames = (int)usbFramesForMs(format.sampleRate, policy.resumeLeadMs);
    plan.fadeOutFrames = (int)usbFramesForMs(format.sampleRate, policy.fadeOutMs);
    plan.fadeInFrames = (int)usbFramesForMs(format.sampleRate, policy.fadeInMs);

    plan.prerollFrames = usbFramesForMs(format.sampleRate, policy.prerollMs);
    plan.prerollBlockFrames = policy.prerollBlockFrames;
    plan.prerollBlocks = (int)ceilDiv(plan.prerollFrames, policy.prerollBlockFrames);

    plan.feedbackBytes = endpoint.hasFeedback ? USB_TRANSPORT_FEEDBACK_BYTES : 0;
    plan.pauseKeepaliveMs = policy.pauseKeepaliveMs;
    plan.feedbackStartGuardMs = endpoint.hasFeedback ? policy.feedbackStartGuardMs : 0;   // without a feedback endpoint there is nothing to hold back
    return plan;
}

const char *usbTransportReasonName(int reason) {
    switch (reason) {
        case USB_PLAN_REASON_NONE: return "none";
        case USB_PLAN_REASON_INVALID: return "invalid-format-or-endpoint";
        case USB_PLAN_REASON_CONTAINER_NARROWER: return "container-narrower-than-source";
        case USB_PLAN_REASON_PACKET_OVER_MAX: return "packet-over-max-packet";
        case USB_PLAN_REASON_RING_TOO_LARGE: return "ring-too-large";
        case USB_PLAN_REASON_FULL_SPEED: return "full-speed-not-supported-yet";
        case USB_PLAN_REASON_BINTERVAL: return "binterval-not-supported-yet";
        case USB_PLAN_REASON_FEEDBACK_WIDTH: return "feedback-width-not-supported-yet";
        default: return "?";
    }
}

void usbTransportPlanToArray(const UsbTransportPlan &plan, int64_t out[USB_PLAN_FIELD_COUNT]) {
    out[USB_PLAN_FIELD_STATUS] = plan.status;
    out[USB_PLAN_FIELD_REASON] = plan.reason;
    out[USB_PLAN_FIELD_ASSUMPTIONS] = plan.assumptions;
    out[USB_PLAN_FIELD_SERVICE_INTERVAL_US] = plan.serviceIntervalUs;
    out[USB_PLAN_FIELD_INTERVALS_PER_SECOND] = plan.intervalsPerSecond;
    out[USB_PLAN_FIELD_NOMINAL_NUM] = plan.nominalFramesNum;
    out[USB_PLAN_FIELD_NOMINAL_DEN] = plan.nominalFramesDen;
    out[USB_PLAN_FIELD_BYTES_PER_FRAME] = plan.bytesPerFrame;
    out[USB_PLAN_FIELD_MAX_FRAMES_PER_PACKET] = plan.maxFramesPerPacket;
    out[USB_PLAN_FIELD_MAX_PACKET_BYTES] = plan.maxPacketBytes;
    out[USB_PLAN_FIELD_MAX_FRAMES_AT_CEILING] = plan.maxFramesPerPacketAtCeiling;
    out[USB_PLAN_FIELD_MAX_PACKET_BYTES_AT_CEILING] = plan.maxPacketBytesAtCeiling;
    out[USB_PLAN_FIELD_PACKETS_PER_URB] = plan.packetsPerUrb;
    out[USB_PLAN_FIELD_URB_MAX_BYTES] = plan.urbMaxBytes;
    out[USB_PLAN_FIELD_URB_SLOT_BYTES] = plan.urbSlotBytes;
    out[USB_PLAN_FIELD_RESIDUAL_CAP_BYTES] = plan.residualCapBytes;
    out[USB_PLAN_FIELD_RING_URBS] = plan.ringUrbs;
    out[USB_PLAN_FIELD_BACKPRESSURE_URBS] = plan.backpressureUrbs;
    out[USB_PLAN_FIELD_LOW_RING_URBS] = plan.lowRingUrbs;
    out[USB_PLAN_FIELD_QUEUE_SOFT_FRAMES] = plan.queueSoftFrames;
    out[USB_PLAN_FIELD_QUEUE_HARD_FRAMES] = plan.queueHardFrames;
    out[USB_PLAN_FIELD_SILENCE_LEAD_FRAMES] = plan.silenceLeadFrames;
    out[USB_PLAN_FIELD_SILENCE_CHUNK_FRAMES] = plan.silenceChunkFrames;
    out[USB_PLAN_FIELD_FADE_OUT_FRAMES] = plan.fadeOutFrames;
    out[USB_PLAN_FIELD_FADE_IN_FRAMES] = plan.fadeInFrames;
    out[USB_PLAN_FIELD_PREROLL_FRAMES] = plan.prerollFrames;
    out[USB_PLAN_FIELD_PREROLL_BLOCK_FRAMES] = plan.prerollBlockFrames;
    out[USB_PLAN_FIELD_PREROLL_BLOCKS] = plan.prerollBlocks;
    out[USB_PLAN_FIELD_FEEDBACK_BYTES] = plan.feedbackBytes;
    out[USB_PLAN_FIELD_RESUME_LEAD_FRAMES] = plan.resumeLeadFrames;
    out[USB_PLAN_FIELD_FEEDBACK_START_GUARD_MS] = plan.feedbackStartGuardMs;
    out[USB_PLAN_FIELD_PAUSE_KEEPALIVE_MS] = plan.pauseKeepaliveMs;
}

void usbTransportPlanDescribe(const UsbTransportPlan &plan, char *out, int capacity) {
    if (plan.status != USB_PLAN_OK) {
        snprintf(out, (size_t)capacity, "plan=unsupported reason=%s", usbTransportReasonName(plan.reason));
        return;
    }
    snprintf(out, (size_t)capacity,
             "plan: interval=%d us (%d/s) nominal=%d/%d frames/interval %d B/frame maxPacket=%d B (%d frames) "
             "urb=%d pkt slot=%d B ring=%d low=%d queue=%lld/%lld frames silence=%d+%d frames resume=%d frames fade=%d/%d pre-roll=%lld frames in %d blocks "
             "feedback=%d B startGuard=%d ms assumptions=0x%x",
             plan.serviceIntervalUs, plan.intervalsPerSecond, plan.nominalFramesNum, plan.nominalFramesDen, plan.bytesPerFrame,
             plan.maxPacketBytes, plan.maxFramesPerPacket, plan.packetsPerUrb, plan.urbSlotBytes, plan.ringUrbs, plan.lowRingUrbs,
             (long long)plan.queueSoftFrames, (long long)plan.queueHardFrames, plan.silenceLeadFrames, plan.silenceChunkFrames, plan.resumeLeadFrames, plan.fadeOutFrames,
             plan.fadeInFrames, (long long)plan.prerollFrames, plan.prerollBlocks, plan.feedbackBytes, plan.feedbackStartGuardMs, plan.assumptions);
}
