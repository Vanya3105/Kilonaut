package com.example.gymtracker

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
}
