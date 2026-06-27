package com.veyq.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cr-0022 — the **L3 "no login" auto-restore** through the real UI + real Android Keystore: floor unlock at
 * "Open straight in" → home → a fresh activity launch (cleared ViewModelStore → a new AppModel re-runs
 * `bootstrap`) **opens straight to home** (no connect, no floor). Uses `createEmptyComposeRule` +
 * `ActivityScenario` so the activity can be closed + relaunched mid-test. Env-gated. (L2 biometric needs an
 * enrolled fingerprint on the emulator — a manual smoke, like Apple's Face ID.)
 */
@RunWith(AndroidJUnit4::class)
class NoLoginAutoRestoreUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun noLoginAutoRestore() {
        val base = args.getString("veyqBase")
        assumeNotNull(base)
        val email = requireNotNull(args.getString("veyqEmail")) { "veyqEmail arg required" }
        val password = requireNotNull(args.getString("veyqPassword")) { "veyqPassword arg required" }
        val secret = requireNotNull(args.getString("veyqSecret")) { "veyqSecret arg required" }
        val shotDir = args.getString("veyqShotDir") // optional

        var scenario = ActivityScenario.launch(MainActivity::class.java)

        // bootstrap finds no prefs (fresh install) → connect → sign in → floor.
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("Instance URL").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("Instance URL").performTextInput(base!!)
        compose.onNodeWithTag("Connect").performClick()
        signIn(email, password)

        // Floor → pick "Open straight in" (L3) and unlock.
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("Secret Key").performTextInput(secret)
        compose.onNodeWithTag("level.NO_LOGIN").performScrollTo().performClick()
        capture("android-trust-l3-01-floor-no-login")
        compose.onNodeWithTag("Unlock").performScrollTo().performClick()

        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("Stop trusting this device").fetchSemanticsNodes().isNotEmpty()
        }
        capture("android-trust-l3-02-home")

        // Cold restart: destroy the activity (clears its ViewModelStore) + launch a fresh one → bootstrap
        // re-runs and auto-restores. The Keystore key + prefs persist across the in-process relaunch.
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(20_000) { compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty() }
        assert(compose.onAllNodesWithTag("Instance URL").fetchSemanticsNodes().isEmpty()) { "no connect on auto-restore" }
        assert(compose.onAllNodesWithText("Unlock").fetchSemanticsNodes().isEmpty()) { "no floor on auto-restore" }
        capture("android-trust-l3-03-autorestore")

        // Cleanup so the device + owner are left clean.
        compose.onNodeWithTag("Stop trusting this device").performClick()
        scenario.close()
    }

    private fun signIn(email: String, password: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("Email").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("Email").performTextInput(email)
        compose.onNodeWithTag("Password").performTextInput(password)
        compose.onNodeWithTag("Sign in").performClick()
    }

    private fun capture(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
}
