package com.signalahead.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun mainScreensOpenWithoutPermissions(){
        ui.onNodeWithText("Your daily signal companion").assertIsDisplayed()
        ui.onNodeWithText("Spots").performClick()
        ui.onNodeWithText("Your signal map").assertIsDisplayed()
        ui.onNodeWithText("Trips").performClick()
        ui.onNodeWithText("Journey journal").assertIsDisplayed()
        ui.onNodeWithText("Settings").performClick()
        ui.onNodeWithText("Journeys you choose").assertIsDisplayed()
        ui.onNodeWithText("Start journey on app open").assertIsDisplayed()
    }
}
