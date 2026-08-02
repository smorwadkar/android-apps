package com.mobildroid.cloudshelf.app.auth

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobildroid.cloudshelf.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the sign-in hang: STS validation previously had no
 * timeout, so a stalled/dead network left the button spinning forever.
 *
 * This launches the real app, fills the IAM form with dummy credentials
 * (always rejected by AWS), taps "Validate & sign in", and asserts that an
 * error surfaces instead of an indefinite spinner. The error can come from
 * either the 15s IamValidationTimeoutException (network dead) or a fast AWS
 * InvalidClientTokenId rejection (network healthy).
 */
@RunWith(AndroidJUnit4::class)
class SignInTimeoutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun signIn_endsInErrorInsteadOfHangingForever() {
        composeRule.textFieldByLabel("Access key ID")
            .performTextClearance()
        composeRule.textFieldByLabel("Access key ID")
            .performTextInput("AKIAIOSFODNN7EXAMPLE")
        composeRule.textFieldByLabel("Secret access key")
            .performTextClearance()
        composeRule.textFieldByLabel("Secret access key")
            .performTextInput("wJalrXUtnFEMIK7MDENGbPxRfiCYEXAMPLEKEY")

        composeRule.onNodeWithText("Validate & sign in").performClick()

        // Old behavior (bug): spinner forever, no error -> waitUntil times out -> test fails.
        // New behavior (fix): either the 15s timeout fires or AWS rejects the keys fast.
        composeRule.waitUntil(timeoutMillis = 40_000) {
            countErrorNodes() > 0
        }

        assertTrue(
            "Expected an error message (timeout or AWS rejection), got an indefinite spinner.",
            countErrorNodes() > 0
        )
    }

    private fun ComposeRule.textFieldByLabel(label: String): SemanticsNodeInteraction =
        onAllNodes(hasText(label)).filterToOne(hasSetTextAction())

    private fun countErrorNodes(): Int =
        expectedErrorFragments.sumOf { fragment ->
            composeRule.onAllNodes(
                hasText(fragment, substring = true, ignoreCase = true)
            ).fetchSemanticsNodes().size
        }

    private companion object {
        val expectedErrorFragments = listOf(
            "Timed out contacting AWS",
            "InvalidClientTokenId",
            "security token",
            "is invalid",
            "connect timed out",
            "Failed to connect",
            "SocketTimeoutException",
            "unable to find valid certification",
            "expired token"
        )
    }
}
