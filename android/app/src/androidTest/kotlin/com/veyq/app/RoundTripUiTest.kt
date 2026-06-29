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
 * The full connect → sign-in → unlock → **Memory shell** UI round-trip against a REAL instance, on the
 * emulator (cr-0016 phase 5, shell since cr-0039) — the Android twin of Apple's `RoundTripUITests`. Env-gated:
 * skipped unless a `veyqBase` is passed as an instrumentation argument, so a hermetic `connectedAndroidTest`
 * doesn't require a backend. Unlike XCUITest's `SecureField`, Compose accepts `performTextInput` into masked
 * fields, so the credentials are typed for real — no prefill seam needed.
 *
 * It doubles as the screenshot capture: the rendered window is saved at each phase
 * (connect / sign-in / unlock / home) into the test storage for `adb pull`.
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

    /** The Memory shell's stable marker: the "Create" toolbar action is always present once browse renders. */
    private fun homeShown() = compose.onAllNodesWithTag("Create").fetchSemanticsNodes().isNotEmpty()

    /** On compact width the rail is a modal drawer; open it to reach the profile controls (sign-out /
     *  untrust). On expanded width the rail is permanent so "Sign out" is already present and this no-ops. */
    private fun openRail() {
        if (compose.onAllNodesWithTag("Sign out").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("Spaces").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("Sign out").fetchSemanticsNodes().isNotEmpty() }
        }
    }

    private fun signOut() {
        openRail()
        compose.onNodeWithTag("Sign out").performClick()
    }

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

        // The Memory shell confirms the operator-blind result: the personal space opens + decrypts on-device.
        compose.waitUntil(timeoutMillis = 20_000) { homeShown() }
        compose.onNodeWithTag("Create").assertIsDisplayed()

        // Create a folder + a Memory (sealed on-device, only ciphertext crosses the wire), then drill into the
        // folder and back via the breadcrumb — proving browse + create + client-side navigation end-to-end.
        // Names carry a per-run suffix so re-runs / running on both the phone + tablet don't hit the
        // per-parent slug-uniqueness (409) on the shared dev instance.
        val tag = (System.currentTimeMillis() % 100000).toString()
        val folder = "Reisen $tag"
        val memory = "Italien $tag"
        createNode("New folder", folder)
        createNode("New memory", memory)
        capture("android-04-home")

        // Drill into the folder: the Memory lives at the root, so it is NOT visible inside the folder.
        compose.onNodeWithTag("folder.$folder").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(memory).fetchSemanticsNodes().isEmpty()
        }
        capture("android-05-folder")
        // Back to the root: the Memory is visible again.
        compose.onNodeWithTag("Back").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(memory).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Open the create menu, pick [menuText] ("New folder" / "New memory"), type [name], confirm, and wait
     *  for the node to appear in the level. */
    private fun createNode(menuText: String, name: String) {
        compose.onNodeWithTag("Create").performClick()
        compose.onNodeWithText(menuText).performClick()
        compose.onNodeWithTag("name.field").performTextInput(name)
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * cr-0021 — the device-trust lifecycle through the real UI + real Android Keystore: floor unlock at
     * "Only my password" → home shows trusted → sign out → re-login **folds straight to home** (no floor)
     * → untrust → re-login lands back on the floor. The trust status + untrust control live in the shell's
     * spaces rail (cr-0039); on compact the rail is a modal drawer, so [openRail] opens it first. Writes the
     * trust-* design references.
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
            compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() || homeShown()
        }
        if (homeShown()) {
            openRail()
            if (compose.onAllNodesWithTag("Stop trusting this device").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("Stop trusting this device").performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodesWithTag("Stop trusting this device").fetchSemanticsNodes().isEmpty()
                }
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

        // 2 — home, now trusted (the untrust control is present in the rail).
        compose.waitUntil(timeoutMillis = 20_000) { homeShown() }
        openRail()
        compose.onNodeWithTag("Stop trusting this device").assertIsDisplayed()
        capture("android-trust-02-home-trusted")

        // 3 — sign out → sign in → folds straight to home (no floor).
        compose.onNodeWithTag("Sign out").performClick()
        signIn(email, password)
        compose.waitUntil(timeoutMillis = 20_000) { homeShown() }
        openRail()
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
