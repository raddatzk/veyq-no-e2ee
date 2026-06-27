package com.veyq.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full connect → sign-in → unlock UI round-trip against a REAL instance, on the emulator (cr-0016
 * phase 5) — the Android twin of Apple's `RoundTripUITests`. Env-gated: skipped unless a `veyqBase` is
 * passed as an instrumentation argument, so a hermetic `connectedAndroidTest` doesn't require a backend.
 * Unlike XCUITest's `SecureField`, Compose accepts `performTextInput` into masked fields, so the
 * credentials are typed for real — no prefill seam needed.
 *
 * It doubles as the screenshot capture: the rendered window is saved at each phase
 * (connect / sign-in / home) into the app's external files dir for `adb pull`.
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.veyqBase=http://10.0.2.2:8080 \
 *     -Pandroid.testInstrumentationRunnerArguments.veyqEmail=kevin@veyq.test \
 *     -Pandroid.testInstrumentationRunnerArguments.veyqPassword='…' \
 *     -Pandroid.testInstrumentationRunnerArguments.veyqSecret='…'
 */
@RunWith(AndroidJUnit4::class)
class RoundTripUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun connectSignInUnlock() {
        val base = args.getString("veyqBase")
        assumeNotNull(base)
        val email = requireNotNull(args.getString("veyqEmail")) { "veyqEmail arg required" }
        val password = requireNotNull(args.getString("veyqPassword")) { "veyqPassword arg required" }
        val secret = requireNotNull(args.getString("veyqSecret")) { "veyqSecret arg required" }

        // Connect screen → capture, then enter the instance URL and connect.
        compose.onNodeWithTag("Instance URL").assertIsDisplayed()
        capture("android-01-connect")
        compose.onNodeWithTag("Instance URL").performTextInput(base!!)
        compose.onNodeWithTag("Connect").performClick()

        // The setup check is a real network call — wait for the sign-in screen to appear.
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag("Email").fetchSemanticsNodes().size == 1
        }

        // Sign-in screen (step 1) → capture, fill email + password, log in.
        capture("android-02-signin")
        compose.onNodeWithTag("Email").performTextInput(email)
        compose.onNodeWithTag("Password").performTextInput(password)
        compose.onNodeWithTag("Sign in").performClick()

        // Unlock floor (step 2) → wait for it, capture, fill the Secret Key, unlock.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty()
        }
        capture("android-03-unlock")
        compose.onNodeWithTag("Secret Key").performTextInput(secret)
        compose.onNodeWithTag("level.SECRET_KEY_EACH_TIME").performScrollTo().performClick()   // no default — pick a level
        compose.onNodeWithTag("Unlock").performScrollTo().performClick()

        // The home screen confirms the operator-blind result.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Unlocked").assertIsDisplayed()
        compose.onNodeWithText(email).assertIsDisplayed()
        capture("android-04-home")
    }

    /**
     * cr-0021 — the device-trust lifecycle through the real UI + real Android Keystore: floor unlock at
     * "Only my password" → home shows trusted → sign out → re-login **folds straight to home** (no floor)
     * → untrust → re-login lands back on the floor. Writes the trust-* design references.
     */
    @Test
    fun trustDeviceLifecycle() {
        val base = args.getString("veyqBase")
        assumeNotNull(base)
        val email = requireNotNull(args.getString("veyqEmail")) { "veyqEmail arg required" }
        val password = requireNotNull(args.getString("veyqPassword")) { "veyqPassword arg required" }
        val secret = requireNotNull(args.getString("veyqSecret")) { "veyqSecret arg required" }

        // Connect → sign in.
        compose.onNodeWithTag("Instance URL").assertIsDisplayed()
        compose.onNodeWithTag("Instance URL").performTextInput(base!!)
        compose.onNodeWithTag("Connect").performClick()
        signIn(email, password)

        // Robustness: a prior run may have left this device trusted (Keystore persists). If sign-in folds
        // straight to home, untrust + sign out + sign in again to reach a known untrusted floor.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("Stop trusting this device").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("Stop trusting this device").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("Stop trusting this device").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("Sign out").performClick()
            signIn(email, password)
        }

        // 1 — floor with the chooser; pick "Only my password" and unlock.
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Secret Key").performTextInput(secret)
        compose.onNodeWithTag("level.ONLY_PASSWORD").performScrollTo().performClick()
        capture("android-trust-01-floor-chooser")
        compose.onNodeWithTag("Unlock").performScrollTo().performClick()

        // 2 — home, now trusted (the untrust control is present).
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Stop trusting this device").assertIsDisplayed()
        capture("android-trust-02-home-trusted")

        // 3 — sign out → sign in → folds straight to home (no floor).
        compose.onNodeWithTag("Sign out").performClick()
        signIn(email, password)
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Stop trusting this device").assertIsDisplayed()
        capture("android-trust-03-relogin-fold")

        // 4 — untrust → sign out → sign in → back to the floor.
        compose.onNodeWithTag("Stop trusting this device").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("Stop trusting this device").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("Sign out").performClick()
        signIn(email, password)
        compose.waitUntil(timeoutMillis = 20_000) { compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
        capture("android-trust-04-floor-after-untrust")
    }

    /** Type email + password on the sign-in screen and tap Sign in (used across the lifecycle re-logins). */
    private fun signIn(email: String, password: String) {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("Email").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag("Email").performTextInput(email)
        compose.onNodeWithTag("Password").performTextInput(password)
        compose.onNodeWithTag("Sign in").performClick()
    }

    /**
     * Save the current rendered window as a PNG through [PlatformTestStorageRegistry] — its output files
     * are copied back to the host by AGP (under app/build/outputs/.../additional_test_output), surviving the
     * post-run uninstall that would wipe the app's own files dir.
     */
    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
