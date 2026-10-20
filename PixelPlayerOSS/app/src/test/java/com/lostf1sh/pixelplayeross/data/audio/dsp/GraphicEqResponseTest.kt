package com.lostf1sh.pixelplayeross.data.audio.dsp

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The response curve the EQ screen draws must describe the filters the engine actually runs.
 *
 * These pin the two properties that make the graph worth drawing: a flat curve is flat, and a boost
 * is a boost at the frequency it was applied to.
 */
class GraphicEqResponseTest {

    private val tenBand = GraphicEqBands.frequenciesFor(10)
    private val fifteenBand = GraphicEqBands.frequenciesFor(15)

    @Test
    fun `a flat curve is silent everywhere`() {
        val curve = GraphicEqResponse.curveDb(tenBand, List(10) { 0 })
        assertThat(curve.max()).isLessThan(0.01f)
        assertThat(curve.min()).isGreaterThan(-0.01f)
    }

    @Test
    fun `boosting one band lifts the response at that band's own frequency`() {
        val gains = List(10) { if (it == 4) 12 else 0 }
        val curve = GraphicEqResponse.curveDb(tenBand, gains)
        val frequencies = GraphicEqResponse.curveFrequenciesHz()

        // The sample nearest the boosted band is itself the peak, and it sits at the gain that
        // was asked for: a peaking filter peaks at its own centre frequency, so anything else
        // would mean the curve is not the engine's.
        val targetHz = tenBand[4].toDouble()
        val nearest = frequencies.indices.minByOrNull { kotlin.math.abs(frequencies[it] - targetHz) }!!
        val peakIndex = curve.indices.maxByOrNull { curve[it] }!!

        assertThat(nearest).isEqualTo(peakIndex)
        assertThat(curve[nearest]).isWithin(0.75f).of(12f)
    }

    @Test
    fun `cutting a band dips the response rather than lifting it`() {
        val gains = List(15) { if (it == 7) -12 else 0 }
        val curve = GraphicEqResponse.curveDb(fifteenBand, gains)
        val frequencies = GraphicEqResponse.curveFrequenciesHz()

        val targetHz = fifteenBand[7].toDouble()
        val nearest = frequencies.indices.minByOrNull { kotlin.math.abs(frequencies[it] - targetHz) }!!
        val dipIndex = curve.indices.minByOrNull { curve[it] }!!

        assertThat(nearest).isEqualTo(dipIndex)
        assertThat(curve[nearest]).isWithin(0.75f).of(-12f)
    }

    @Test
    fun `the curve is sampled across the audible band and is the same length in both modes`() {
        val ten = GraphicEqResponse.curveDb(tenBand, List(10) { 0 })
        val fifteen = GraphicEqResponse.curveDb(fifteenBand, List(15) { 0 })

        assertThat(ten.size).isEqualTo(fifteen.size)
        val frequencies = GraphicEqResponse.curveFrequenciesHz()
        assertThat(frequencies.first()).isWithin(1.0).of(20.0)
        assertThat(frequencies.last()).isWithin(1.0).of(20_000.0)
    }
}
