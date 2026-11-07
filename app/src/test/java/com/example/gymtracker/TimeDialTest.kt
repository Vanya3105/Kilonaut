package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeDialTest {
    @Test
    fun angle_midnightAtTheTop_clockwise_toFiveMinutes() {
        assertEquals(0, minutesAtAngle(0f))
        assertEquals(6 * 60, minutesAtAngle(90f))
        assertEquals(12 * 60, minutesAtAngle(180f))
        assertEquals(18 * 60, minutesAtAngle(-90f))
        assertEquals("rounds to the nearest 5 min", 7 * 60 + 30, minutesAtAngle(112.6f))
        assertEquals("just before the top is 23:55, not 24:00", 23 * 60 + 55, minutesAtAngle(358.8f))
        assertEquals("the very top again", 0, minutesAtAngle(359.9f))
    }

    @Test
    fun partOfDay_names() {
        assertEquals("Morning", partOfDay(7 * 60 + 30))
        assertEquals("Afternoon", partOfDay(13 * 60))
        assertEquals("Evening", partOfDay(19 * 60))
        assertEquals("Night", partOfDay(23 * 60))
        assertEquals("Night", partOfDay(3 * 60))
    }
}
