package net.i2p.data

import org.scalatest.FunSpec
import org.scalatest.Matchers

/**
 * @author str4d
 */
class SigningPrivateKeySpec extends FunSpec with Matchers {
    val signingPrivateKey = new SigningPrivateKey

    describe("A SigningPrivateKey") {
        it("should be 20 bytes long") {
            signingPrivateKey should have length (20)
        }
    }
}
