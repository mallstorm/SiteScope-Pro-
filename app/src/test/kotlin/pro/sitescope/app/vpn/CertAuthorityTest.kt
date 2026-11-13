package pro.sitescope.app.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class CertAuthorityTest {
    @Test fun generatedCaIsAValidSelfSignedCaCertificate() {
        val m = CertAuthority.generate("SiteScope Pro CA Test")
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(m.certificatePem.toByteArray())) as X509Certificate
        cert.verify(cert.publicKey)                                       // подпись корректна
        assertTrue("basicConstraints: CA", cert.basicConstraints >= 0)
        assertTrue("keyCertSign", cert.keyUsage != null && cert.keyUsage[5])
        assertTrue(cert.subjectX500Principal.name.contains("SiteScope Pro CA"))
        cert.checkValidity()
        assertArrayEquals("DER из PEM совпадает с разобранным сертификатом", cert.encoded, CertAuthority.derOf(m.certificatePem))
    }
}
