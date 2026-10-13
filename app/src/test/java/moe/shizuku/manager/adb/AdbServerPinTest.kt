package moe.shizuku.manager.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import rocks.pkb.plektra.inputmethod.EmbeddedAdbShell
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdbServerPinTest {

    companion object {
        private fun cert(cn: String): X509Certificate {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val holder = X509v3CertificateBuilder(
                X500Name("CN=$cn"), BigInteger.ONE, Date(0), Date(4_000_000_000_000L), Locale.ROOT,
                X500Name("CN=$cn"), SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            return CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
        }

        private val adbd by lazy { cert("adbd") }
        private val other by lazy { cert("other") }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var pins: PreferenceAdbServerPinStore

    @Before
    fun setUp() {
        EmbeddedAdbInit.prefs(context).edit().clear().commit()
        pins = PreferenceAdbServerPinStore(EmbeddedAdbInit.prefs(context))
    }

    @Test
    fun pinIsTheSha256OfTheSubjectPublicKeyInfo() {
        val pin = AdbServerPin.of(adbd)
        assertEquals(64, pin.length)
        assertEquals(pin, AdbServerPin.of(adbd))
        assertNotEquals(pin, AdbServerPin.of(other))
    }

    @Test
    fun pinnedCertIsAcceptedAndAnyOtherRefused() {
        pins.storeConnectPin(AdbServerPin.of(adbd))
        val tm = PinnedAdbServerTrustManager(pins)

        tm.checkServerTrusted(arrayOf(adbd), "RSA")
        try {
            tm.checkServerTrusted(arrayOf(other), "RSA")
            fail("a cert that does not match the pin must be refused")
        } catch (_: CertificateException) {
        }
        try {
            tm.checkServerTrusted(emptyArray(), "RSA")
            fail("an empty chain must be refused")
        } catch (_: CertificateException) {
        }
        tm.commitFirstUse()
        assertEquals(AdbServerPin.of(adbd), pins.connectPin)
    }

    @Test
    fun withoutConnectPinTheFirstCommittedCertIsPinned() {
        pins.recordPairing(AdbServerPin.of(adbd))
        val first = PinnedAdbServerTrustManager(pins)
        first.checkServerTrusted(arrayOf(other), "RSA")
        assertNull("nothing is pinned before the ADB handshake completes", pins.connectPin)
        first.commitFirstUse()
        assertEquals(AdbServerPin.of(other), pins.connectPin)

        try {
            PinnedAdbServerTrustManager(pins).checkServerTrusted(arrayOf(adbd), "RSA")
            fail("once pinned, a different cert must be refused")
        } catch (_: CertificateException) {
        }
    }

    @Test
    fun pairingAgainRestartsTrustOnFirstUse() {
        pins.recordPairing(AdbServerPin.of(adbd))
        pins.storeConnectPin(AdbServerPin.of(adbd))

        pins.recordPairing(AdbServerPin.of(other))

        assertEquals(AdbServerPin.of(other), pins.pairingPin)
        assertNull(pins.connectPin)
    }

    @Test
    fun pinsLiveInTheBackupExcludedBrokerPrefsAndForgetPairingDropsThem() {
        pins.recordPairing(AdbServerPin.of(adbd))
        pins.storeConnectPin(AdbServerPin.of(adbd))
        assertTrue(EmbeddedAdbInit.prefs(context).contains(PreferenceAdbServerPinStore.CONNECT_KEY))

        EmbeddedAdbShell.forgetPairing(context)

        assertNull(pins.pairingPin)
        assertNull(pins.connectPin)
    }

    @Test
    fun verifyAcceptsOnlyTheExactMarker() {
        assertTrue(EmbeddedAdbShell.isVerifyOutput("pastiera_verify\n"))
        assertTrue(EmbeddedAdbShell.isVerifyOutput("pastiera_verify\r\n"))
        assertTrue(EmbeddedAdbShell.isVerifyOutput("pastiera_verify"))
        assertFalse(EmbeddedAdbShell.isVerifyOutput(""))
        assertFalse(EmbeddedAdbShell.isVerifyOutput("OK\n"))
        assertFalse(EmbeddedAdbShell.isVerifyOutput("pastiera_verify\nanything else\n"))
        assertFalse(EmbeddedAdbShell.isVerifyOutput(" pastiera_verify\n"))
    }
}
