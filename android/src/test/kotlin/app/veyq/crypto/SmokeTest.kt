package app.veyq.crypto

import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun suiteId() {
        assertEquals("v1", VeyqCrypto.SUITE_ID)
    }

    // Proves BouncyCastle's ML-KEM-768 (FIPS 203) round-trips on the JVM — the PQC primitive the
    // v1 hybrid KEM needs on Android.
    @Test
    fun mlkem768RoundTrip() {
        val rnd = SecureRandom()
        val kpg = MLKEMKeyPairGenerator()
        kpg.init(MLKEMKeyGenerationParameters(rnd, MLKEMParameters.ml_kem_768))
        val kp = kpg.generateKeyPair()
        val enc = MLKEMGenerator(rnd).generateEncapsulated(kp.public)
        val recovered = MLKEMExtractor(kp.private as MLKEMPrivateKeyParameters).extractSecret(enc.encapsulation)
        assertContentEquals(enc.secret, recovered)
    }
}
