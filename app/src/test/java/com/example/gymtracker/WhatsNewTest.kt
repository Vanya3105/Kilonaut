package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Which versions' notes an update shows. */
@RunWith(RobolectricTestRunner::class)
class WhatsNewTest {
    private fun versions(current: String, since: String?) = WhatsNew.sections(current, since).map { it.first }

    @Test
    fun anUpdateShowsEveryVersionSkipped() {
        assertEquals(listOf("1.0.0-beta.2"), versions("1.0.0-beta.2", "1.0.0-beta.1"))
        assertEquals(listOf("1.0.0-beta.2", "1.0.0-beta.1"), versions("1.0.0-beta.2", "3.8.0"))
        assertEquals(listOf("1.0.0-beta.2", "1.0.0-beta.1"), versions("1.0.0-beta.2", null))
        assertEquals(listOf("1.0.0-beta.1"), versions("1.0.0-beta.1", "3.8.0"))
        assertEquals(listOf("1.0.0-beta.3", "1.0.0-beta.2"), versions("1.0.0-beta.3", "1.0.0-beta.1"))
    }
}
