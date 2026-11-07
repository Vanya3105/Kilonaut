package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun comparesVersionsNumberByNumber() {
        assertTrue(AppUpdate.isNewer("v3.9.0", "3.8.0"))
        assertTrue(AppUpdate.isNewer("3.10.0", "3.9.1"))
        assertTrue(AppUpdate.isNewer("4.0", "3.9.9"))
        assertFalse(AppUpdate.isNewer("3.8.0", "3.8.0"))
        assertFalse(AppUpdate.isNewer("v3.7.0", "3.8.0"))
        assertFalse(AppUpdate.isNewer("3.8", "3.8.0"))
    }

    @Test
    fun betas_comeAfterTheOldNumbering_andBeforeTheirRelease() {
        assertTrue("3.8.0 users are offered the first beta", AppUpdate.isNewer("v1.0.0-beta.1", "3.8.0"))
        assertFalse(AppUpdate.isNewer("v3.8.0", "1.0.0-beta.1"))
        assertTrue(AppUpdate.isNewer("1.0.0-beta.2", "1.0.0-beta.1"))
        assertTrue(AppUpdate.isNewer("1.0.0-beta.10", "1.0.0-beta.9"))
        assertFalse(AppUpdate.isNewer("1.0.0-beta.1", "1.0.0-beta.1"))
        assertTrue("the release beats its betas", AppUpdate.isNewer("1.0.0", "1.0.0-beta.4"))
        assertFalse(AppUpdate.isNewer("1.0.0-beta.4", "1.0.0"))
        assertTrue(AppUpdate.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun shownVersion_readsAsWords() {
        assertEquals("1.0.0 beta 1", AppUpdate.shownVersion("1.0.0-beta.1"))
        assertEquals("1.0.0", AppUpdate.shownVersion("1.0.0"))
    }
}
