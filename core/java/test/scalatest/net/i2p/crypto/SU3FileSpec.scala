package net.i2p.crypto

import java.io.File

import org.scalatest.FunSpec
import org.scalatest.Matchers


class SU3FileSpec extends FunSpec with Matchers {

  def cheater(methodName : String, parameters: (AnyRef,Class[_])*): AnyRef = {
    val parameterValues = parameters.map(_._1)
    val parameterTypes = parameters.map(_._2)
    val method = classOf[SU3File].getDeclaredMethod(methodName, parameterTypes:_*)
    method.setAccessible(true)
    method.invoke(classOf[SU3File], parameterValues:_*)
  }

  describe("SU3File") {
    val certFileUrl = getClass.getResource("/resources/meeh_at_mail.i2p.crt")
    val certFile = new File(certFileUrl.toURI)

    val seedFileUrl = getClass.getResource("/resources/i2pseeds.su3")
    val seedFile = new File(seedFileUrl.toURI)

    it("should be able to verify a valid file") {
      cheater("verifySigCLI", (seedFile.getAbsolutePath, classOf[String]), (certFile.getAbsolutePath, classOf[String]))
    }
  }
}