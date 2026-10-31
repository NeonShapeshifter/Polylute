// B46-A3: the transport plan for Kotlin. One function over makeUsbTransportPlan (usb-pcm-packetizer.h), so the preflight that
// decides before the claim whether a DAC can carry a format asks the same code the writer plans with. There is no Kotlin
// implementation of the arithmetic, and none to fall back to.
//
// Built into the app's native library and, for the JVM unit tests, into a host copy by the driver module's
// `buildHostTransportPlanLib` task (clang from the NDK; this file and the packetizer need no C++ library).

#include <jni.h>

#include "usb-pcm-packetizer.h"

extern "C" {

/**
 * The plan of a format on an endpoint, as the flat array of UsbPlanField. Unsupported plans come back too (status, reason) so
 * the caller can say why. Policy: the output's own (usbTransportDefaultPolicy) with [prerollMs] as the requested pre-roll.
 */
JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbTransportPlanner_nativePlan(
        JNIEnv *env, jclass, jint sampleRate, jint channels, jint containerBytes, jint sourceBits, jint busSpeed,
        jint maxPacketBytes, jint bInterval, jboolean hasFeedback, jint feedbackBytes, jint prerollMs) {
    UsbTransportFormat format = {};
    format.sampleRate = sampleRate;
    format.channels = channels;
    format.containerBytes = containerBytes;
    format.sourceBits = sourceBits;
    UsbTransportEndpoint endpoint = {};
    endpoint.speed = busSpeed;
    endpoint.maxPacketBytes = maxPacketBytes;
    endpoint.bInterval = bInterval;
    endpoint.hasFeedback = hasFeedback == JNI_TRUE;
    endpoint.feedbackBytes = feedbackBytes;
    UsbTransportPolicy policy = usbTransportDefaultPolicy();
    policy.prerollMs = prerollMs;

    int64_t fields[USB_PLAN_FIELD_COUNT];
    usbTransportPlanToArray(makeUsbTransportPlan(format, endpoint, policy), fields);
    jlong copy[USB_PLAN_FIELD_COUNT];
    for (int i = 0; i < USB_PLAN_FIELD_COUNT; i++) copy[i] = (jlong)fields[i];
    jlongArray out = env->NewLongArray(USB_PLAN_FIELD_COUNT);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, USB_PLAN_FIELD_COUNT, copy);
    return out;
}

/** The name of a UsbTransportReason, for a log line (the names live next to the reasons, not in Kotlin). */
JNIEXPORT jstring JNICALL
Java_com_decent_usbaudio_UsbTransportPlanner_nativeReasonName(JNIEnv *env, jclass, jint reason) {
    return env->NewStringUTF(usbTransportReasonName(reason));
}

/**
 * The producer queue limits of the default policy at [sampleRate], `[soft, hard]` in frames, for a thread made before any stream's plan
 * exists (a unit test, a thread without a stream).
 */
JNIEXPORT jlongArray JNICALL
Java_com_decent_usbaudio_UsbTransportPlanner_nativeDefaultQueueFrames(JNIEnv *env, jclass, jint sampleRate) {
    const UsbTransportPolicy policy = usbTransportDefaultPolicy();
    const jlong limits[2] = {(jlong)usbFramesForMs(sampleRate, policy.queueSoftMs), (jlong)usbFramesForMs(sampleRate, policy.queueHardMs)};
    jlongArray out = env->NewLongArray(2);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 2, limits);
    return out;
}

/** How many fields a plan array has: Kotlin checks its own list of indexes against it. */
JNIEXPORT jint JNICALL
Java_com_decent_usbaudio_UsbTransportPlanner_nativeFieldCount(JNIEnv *, jclass) {
    return USB_PLAN_FIELD_COUNT;
}

}  // extern "C"
