package net.i2p.data

import org.scalatest.FunSpec
import org.scalatest.Matchers

/**
 * @author str4d
 */
class PublicKeySpec extends FunSpec with Matchers {
    val publicKey = new PublicKey

    describe("A PublicKey") {
        it("should be 256 bytes long") {
            publicKey should have length (256)
        }
    }
}
