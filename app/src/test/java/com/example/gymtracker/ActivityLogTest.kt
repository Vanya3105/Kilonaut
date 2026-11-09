package com.example.gymtracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** The activity kept on the phone: newest first, capped, and read back as written. */
class ActivityLogTest {
    private fun item(at: Long, type: String = "like") = ActivityLog.Item(type, "u$at", "hi", "owner", "p$at", at)

    @Test
    fun newestFirstAndCapped() {
        val old = (1L..79L).map { item(it) }.sortedByDescending { it.at }
        val merged = ActivityLog.merged(old, listOf(item(100), item(90)))
        assertEquals(80, merged.size)
        assertEquals(listOf(100L, 90L, 79L), merged.take(3).map { it.at })
    }

    @Test
    fun roundTrips() {
        val items = listOf(item(5, "follow").copy(accepted = true), item(3, "reply"))
        assertEquals(items, ActivityLog.parse(ActivityLog.encode(items)))
        assertEquals(emptyList<ActivityLog.Item>(), ActivityLog.parse("not json"))
    }
}
