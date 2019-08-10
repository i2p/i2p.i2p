package net.i2p.crypto

import java.io.File
import java.security.cert.X509Certificate

import org.scalatest.FunSpec
import org.scalatest.Matchers


class CertUtilSpec extends FunSpec with Matchers {

  describe("CertUtil") {
    val certFileUrl = getClass.getResource("/meeh_at_mail.i2p.crt")
    val certFile = new File(certFileUrl.toURI)

    it("should be able to read a certificate") {
      val cert: X509Certificate = CertUtil.loadCert(certFile)
      assert(cert.getSubjectDN.toString === "CN=meeh@mail.i2p, OU=I2P, O=I2P Anonymous Network, L=XX, ST=XX, C=XX")
    }

    it("should be able to tell if it's revoked or not") {
      val cert: X509Certificate = CertUtil.loadCert(certFile)
      assert(CertUtil.isRevoked(cert) === false)
    }

  }
}
