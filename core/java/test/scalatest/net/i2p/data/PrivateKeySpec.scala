package net.i2p.data

import org.scalatest.FunSpec
import org.scalatest.Matchers

/**
 * @author str4d
 */
class PrivateKeySpec extends FunSpec with Matchers {
    val privateKey = new PrivateKey

    describe("A PrivateKey") {
        it("should be 256 bytes long") {
            privateKey should have length (256)
        }
    }
}
