package com.lostf1sh.pixelplayeross.data.volume

import com.google.common.truth.Truth.assertThat
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxOutsideNotice.OfferStyle
import com.lostf1sh.pixelplayeross.data.volume.SafetyMaxOutsideNotice.Where
import org.junit.jupiter.api.Test

/** B69: the pure rules of what is said outside the app, per ceiling episode. */
class SafetyMaxOutsideNoticeTest {

    @Test
    fun `one visible notice per episode, only with another app in front`() {
        val notice = SafetyMaxOutsideNotice()
        assertThat(notice.onRefusedRaise(1_000, Where.OTHER_APP)).isTrue()
        assertThat(notice.onRefusedRaise(1_400, Where.OTHER_APP)).isFalse()
        assertThat(notice.onRefusedRaise(1_800, Where.OTHER_APP)).isFalse()
        val elsewhere = SafetyMaxOutsideNotice()
        assertThat(elsewhere.onRefusedRaise(1_000, Where.APP_IN_FRONT)).isFalse()
        assertThat(elsewhere.onRefusedRaise(1_400, Where.LOCKED)).isFalse()
        // Unlocked into another app within the episode: the notice has not been shown yet, so it is now.
        assertThat(elsewhere.onRefusedRaise(1_800, Where.OTHER_APP)).isTrue()
    }

    @Test
    fun `never on the Dedicated appliance`() {
        val notice = SafetyMaxOutsideNotice()
        assertThat(notice.onRefusedRaise(1_000, Where.OTHER_APP, dedicated = true)).isFalse()
        assertThat(notice.onOverrideAsked(1_400, Where.OTHER_APP, dedicated = true)).isNull()
        assertThat(notice.onOverrideAsked(1_800, Where.LOCKED, dedicated = true)).isNull()
        assertThat(notice.activeOffer(1_900)).isNull()
    }

    @Test
    fun `the action is offered once per episode, in the style of where the ask was made`() {
        val notice = SafetyMaxOutsideNotice()
        assertThat(notice.activeOffer(900)).isNull()
        assertThat(notice.onOverrideAsked(1_000, Where.OTHER_APP, dedicated = false)).isEqualTo(OfferStyle.HEADS_UP)
        assertThat(notice.onOverrideAsked(2_000, Where.OTHER_APP, dedicated = false)).isNull()
        assertThat(notice.activeOffer(2_100)).isEqualTo(OfferStyle.HEADS_UP)
        val locked = SafetyMaxOutsideNotice()
        assertThat(locked.onOverrideAsked(1_000, Where.LOCKED, dedicated = false)).isEqualTo(OfferStyle.LOCK_SCREEN)
        assertThat(SafetyMaxOutsideNotice().onOverrideAsked(1_000, Where.APP_IN_FRONT, dedicated = false)).isNull()
    }

    @Test
    fun `an episode ends when it is ended, or after a minute without a refused raise`() {
        val notice = SafetyMaxOutsideNotice()
        assertThat(notice.onRefusedRaise(1_000, Where.OTHER_APP)).isTrue()
        notice.onOverrideAsked(1_500, Where.OTHER_APP, dedicated = false)
        notice.endEpisode()
        assertThat(notice.activeOffer(1_600)).isNull()
        assertThat(notice.onRefusedRaise(1_700, Where.OTHER_APP)).isTrue()
        // Presses keep the episode alive; a minute of quiet ends it.
        assertThat(notice.onRefusedRaise(1_700 + 50_000, Where.OTHER_APP)).isFalse()
        assertThat(notice.onRefusedRaise(1_700 + 100_000, Where.OTHER_APP)).isFalse()
        notice.onOverrideAsked(1_700 + 100_100, Where.OTHER_APP, dedicated = false)
        assertThat(notice.activeOffer(1_700 + 100_100 + SafetyMaxOutsideNotice.EPISODE_IDLE_MS + 1)).isNull()
        assertThat(notice.onRefusedRaise(1_700 + 100_100 + SafetyMaxOutsideNotice.EPISODE_IDLE_MS + 2, Where.OTHER_APP)).isTrue()
    }
}
