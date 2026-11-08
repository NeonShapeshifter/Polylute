package com.lostf1sh.pixelplayeross.presentation.screens

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** B61 item 4: what a typed number is. The exact value typed, in whole hertz, tenths of a dB and hundredths of Q, or the reason it is not one. */
class B61DacEqNumbersTest {
    @Test fun `hertz are whole numbers inside the limits, exactly as typed`() {
        assertThat(DacEqNumbers.hz("200", 20, 20_000)).isEqualTo(DacEqTyped.Value(200))
        assertThat(DacEqNumbers.hz("5500", 20, 20_000)).isEqualTo(DacEqTyped.Value(5500))
        assertThat(DacEqNumbers.hz("20", 20, 20_000)).isEqualTo(DacEqTyped.Value(20))
        assertThat(DacEqNumbers.hz("20000", 20, 20_000)).isEqualTo(DacEqTyped.Value(20_000))
        assertThat(DacEqNumbers.hz("19", 20, 20_000)).isEqualTo(DacEqTyped.OutOfRange)
        assertThat(DacEqNumbers.hz("20001", 20, 20_000)).isEqualTo(DacEqTyped.OutOfRange)
        assertThat(DacEqNumbers.hz("200.5", 20, 20_000)).isEqualTo(DacEqTyped.TooManyDecimals)
        assertThat(DacEqNumbers.hz("1e3", 20, 20_000)).isEqualTo(DacEqTyped.NotANumber)
        assertThat(DacEqNumbers.hz("", 20, 20_000)).isEqualTo(DacEqTyped.Empty)
    }

    @Test fun `gain is in tenths of a dB, signed, one decimal at most, inside the limits`() {
        assertThat(DacEqNumbers.gainTenths("-3.5", -120, 120)).isEqualTo(DacEqTyped.Value(-35))
        assertThat(DacEqNumbers.gainTenths("3.5", -120, 120)).isEqualTo(DacEqTyped.Value(35))
        assertThat(DacEqNumbers.gainTenths("-12", -120, 120)).isEqualTo(DacEqTyped.Value(-120))
        assertThat(DacEqNumbers.gainTenths("12", -120, 120)).isEqualTo(DacEqTyped.Value(120))
        assertThat(DacEqNumbers.gainTenths("12.1", -120, 120)).isEqualTo(DacEqTyped.OutOfRange)
        assertThat(DacEqNumbers.gainTenths("-12.1", -120, 120)).isEqualTo(DacEqTyped.OutOfRange)
        assertThat(DacEqNumbers.gainTenths("-3.55", -120, 120)).isEqualTo(DacEqTyped.TooManyDecimals)
        assertThat(DacEqNumbers.gainTenths("-0.5", -120, 120)).isEqualTo(DacEqTyped.Value(-5))
        assertThat(DacEqNumbers.gainTenths("0", -120, 120)).isEqualTo(DacEqTyped.Value(0))
        assertThat(DacEqNumbers.gainTenths("-", -120, 120)).isEqualTo(DacEqTyped.Empty)       // a minus on its way to a number is not an error yet
        assertThat(DacEqNumbers.gainTenths("-.", -120, 120)).isEqualTo(DacEqTyped.Empty)
        assertThat(DacEqNumbers.gainTenths("abc", -120, 120)).isEqualTo(DacEqTyped.NotANumber)
    }

    @Test fun `Q is in hundredths, two decimals at most, 0 point 1 to 10, and the comma is the point`() {
        assertThat(DacEqNumbers.qHundredths("0.80", 10, 1000)).isEqualTo(DacEqTyped.Value(80))
        assertThat(DacEqNumbers.qHundredths("3.00", 10, 1000)).isEqualTo(DacEqTyped.Value(300))
        assertThat(DacEqNumbers.qHundredths("3,01", 10, 1000)).isEqualTo(DacEqTyped.Value(301))
        assertThat(DacEqNumbers.qHundredths("4", 10, 1000)).isEqualTo(DacEqTyped.Value(400))
        assertThat(DacEqNumbers.qHundredths("5", 10, 1000)).isEqualTo(DacEqTyped.Value(500))
        assertThat(DacEqNumbers.qHundredths("10", 10, 1000)).isEqualTo(DacEqTyped.Value(1000))
        assertThat(DacEqNumbers.qHundredths("0.1", 10, 1000)).isEqualTo(DacEqTyped.Value(10))
        assertThat(DacEqNumbers.qHundredths("0.09", 10, 1000)).isEqualTo(DacEqTyped.OutOfRange)
        assertThat(DacEqNumbers.qHundredths("10.01", 10, 1000)).isEqualTo(DacEqTyped.OutOfRange)
        assertThat(DacEqNumbers.qHundredths("3.001", 10, 1000)).isEqualTo(DacEqTyped.TooManyDecimals)
        assertThat(DacEqNumbers.qHundredths("-1", 10, 1000)).isEqualTo(DacEqTyped.OutOfRange)
    }

    @Test fun `the plain text a field starts from is the exact number`() {
        assertThat(DacEqNumbers.hzText(5472)).isEqualTo("5472")
        assertThat(DacEqNumbers.gainText(-35)).isEqualTo("-3.5")
        assertThat(DacEqNumbers.gainText(0)).isEqualTo("0.0")
        assertThat(DacEqNumbers.qText(533)).isEqualTo("5.33")
    }
}
