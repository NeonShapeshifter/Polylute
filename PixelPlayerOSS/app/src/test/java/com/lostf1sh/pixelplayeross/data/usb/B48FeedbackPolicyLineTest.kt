package com.lostf1sh.pixelplayeross.data.usb

import com.decent.usbaudio.UsbTransportPlan
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * B48-3: the `FEEDBACK_POLICY|…` line of the debug bridge prints `nominal=` from the stream's transport plan (`NOMINAL_NUM / NOMINAL_DEN`), not as `rate / 8000.0`.
 * The line is built by `FeedbackPolicyReport` (debug source set), reached by reflection so that the release unit tests still compile.
 */
class B48FeedbackPolicyLineTest {

    private fun line(stats: LongArray?, plan: LongArray?, tag: String?): String {
        val type = runCatching { Class.forName("com.lostf1sh.pixelplayeross.dap.admin.FeedbackPolicyReport") }.getOrNull()
        // a release variant has no debug source set; in a debug build the report must exist
        if (type == null) assumeTrue(com.lostf1sh.pixelplayeross.BuildConfig.DEBUG, "release variant: the debug source set is not part of it")   // skip there; in a debug build the check below fails
        checkNotNull(type) { "FeedbackPolicyReport is missing from a debug build" }
        val instance = type.getField("INSTANCE").get(null)
        return type.getMethod("line", LongArray::class.java, LongArray::class.java, String::class.java).invoke(instance, stats, plan, tag) as String
    }

    /** `[mode, stuck, episodes, ?, stuckMs, …, lastFpmf(9), rate(10), sending(11), rejected(12), streaks(13), lastRejected(14)]`, fpmf in millionths. */
    private fun stats(rate: Long) = LongArray(15).also {
        it[0] = 3          // auto
        it[1] = 0
        it[2] = 2
        it[4] = 1_500
        it[9] = 5_512_500
        it[10] = rate
        it[11] = 5_512_500
        it[12] = 4
        it[13] = 1
        it[14] = 5_787_500
    }

    private fun plan(num: Long, den: Long, status: Long = 0) = LongArray(UsbTransportPlan.Field.COUNT).also {
        it[UsbTransportPlan.Field.STATUS] = status
        it[UsbTransportPlan.Field.NOMINAL_NUM] = num
        it[UsbTransportPlan.Field.NOMINAL_DEN] = den
    }

    @Test
    fun `nominal is the plan's frames per interval and the whole line is pinned`() {
        assertThat(line(stats(44_100), plan(44_100, 8_000), "t1")).isEqualTo(
            "FEEDBACK_POLICY|mode=auto|stuck=false|lastFpmf=5.5125|nominal=5.5125|sending=5.5125|episodes=2|stuckMs=1500|rejected=4|streaks=1|lastRejected=5.7875|tag=t1",
        )
    }

    @Test
    fun `a stream whose service interval is not 125 microseconds prints its own nominal, not the rate over 8000`() {
        // a 1 ms interval: 44 100 / 1 000 = 44.1 frames per interval; HEAD printed rate / 8000 = 5.5125 whatever the plan said
        val text = line(stats(44_100), plan(44_100, 1_000), "t2")
        assertThat(text).contains("|nominal=44.1000|")
        assertThat(text).doesNotContain("|nominal=5.5125|")
    }

    @Test
    fun `no stream, no plan, an unsupported plan or a plan with no interval print none`() {
        assertThat(line(stats(44_100), null, null)).contains("|nominal=none|")
        assertThat(line(stats(44_100), LongArray(0), null)).contains("|nominal=none|")
        assertThat(line(stats(44_100), plan(44_100, 8_000, status = 1), null)).contains("|nominal=none|")
        assertThat(line(stats(44_100), plan(44_100, 0), null)).contains("|nominal=none|")
        assertThat(line(null, null, null)).startsWith("FEEDBACK_POLICY|mode=?|stuck=?|lastFpmf=none|nominal=none|sending=none|")
    }

    @Test
    fun `the plan reads its nominal as the exact ratio`() {
        assertThat(UsbTransportPlan(plan(96_000, 8_000)).nominalFramesPerInterval).isEqualTo(12.0)
        assertThat(UsbTransportPlan(plan(44_100, 8_000)).nominalFramesPerInterval).isWithin(1e-12).of(5.5125)
        assertThat(UsbTransportPlan(plan(44_100, 8_000, status = 2)).nominalFramesPerInterval).isEqualTo(0.0)
    }
}
