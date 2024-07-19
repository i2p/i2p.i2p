package net.i2p.crypto

import java.io.File
import java.security.cert.X509Certificate

import org.scalatest.FunSpec
import org.scalatest.Matchers


class CertUtilSpec extends FunSpec with Matchers {

  describe("CertUtil") {
    val certFileUrl = getClass.getResource("/reseed_at_diva.exchange.crt")
    val certFile = new File(certFileUrl.toURI)

    it("should be able to read a certificate") {
      val cert: X509Certificate = CertUtil.loadCert(certFile)
      assert(cert.getSubjectDN.toString === "CN=reseed@diva.exchange, OU=I2P, O=I2P Anonymous Network, STREET=XX, L=XX, C=XX")
    }

    it("should be able to tell if it's revoked or not") {
      val cert: X509Certificate = CertUtil.loadCert(certFile)
      assert(CertUtil.isRevoked(cert) === false)
    }

  }
}
