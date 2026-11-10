package com.example.gymtracker

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The rules screen inside a page that already scrolls (Profile → Friends) used to crash the app. */
@RunWith(RobolectricTestRunner::class)
class CommunityRulesGateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun insideAScrollingPage() {
        compose.setContent {
            GymTheme(LightPalette) {
                Column(Modifier.verticalScroll(rememberScrollState())) { CommunityRulesGate() }
            }
        }
        compose.onNodeWithText("I agree").assertExists()
    }
}
