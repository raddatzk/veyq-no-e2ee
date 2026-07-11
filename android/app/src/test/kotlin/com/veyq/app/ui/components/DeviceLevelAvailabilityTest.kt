package com.veyq.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-unlock-level availability gate (audit P2f) — the JVM-testable core of the DeviceSecurityChooser
 * gating. Pins that **No Login (L3)** is offered only on a device with a secure lock screen (its Keystore key
 * is stored auth-less, so a device credential is the only barrier), mirroring Apple's chooser, and that
 * **Biometric (L2)** still needs an enrolled biometric while the other levels are always offerable.
 */
class DeviceLevelAvailabilityTest {

    @Test
    fun noLoginRequiresASecureLockScreen() {
        assertFalse(
            "No Login must be blocked without a secure lock screen — its key is auth-less",
            deviceLevelAvailable(DeviceUnlockLevel.NO_LOGIN, biometricAvailable = true, deviceSecure = false),
        )
        assertTrue(
            deviceLevelAvailable(DeviceUnlockLevel.NO_LOGIN, biometricAvailable = false, deviceSecure = true),
        )
    }

    @Test
    fun biometricRequiresAnEnrolledBiometricIndependentOfDeviceSecurity() {
        assertFalse(deviceLevelAvailable(DeviceUnlockLevel.BIOMETRIC, biometricAvailable = false, deviceSecure = true))
        assertTrue(deviceLevelAvailable(DeviceUnlockLevel.BIOMETRIC, biometricAvailable = true, deviceSecure = false))
    }

    @Test
    fun secretKeyAndPasswordAreAlwaysOfferable() {
        for (secure in listOf(true, false)) {
            assertTrue(deviceLevelAvailable(DeviceUnlockLevel.SECRET_KEY_EACH_TIME, biometricAvailable = false, deviceSecure = secure))
            assertTrue(deviceLevelAvailable(DeviceUnlockLevel.ONLY_PASSWORD, biometricAvailable = false, deviceSecure = secure))
        }
    }
}
