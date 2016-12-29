package net.i2p.data

import org.scalatest.FunSpec
import org.scalatest.Matchers

/**
 * @author str4d
 */
class SignatureSpec extends FunSpec with Matchers {
    val signature = new Signature

    describe("A Signature") {
        it("should be 40 bytes long") {
            signature should have length (40)
        }
    }
}
