package net.i2p.data

import org.scalatest.FunSpec
import org.scalatest.Matchers

/**
 * @author str4d
 */
class SessionKeySpec extends FunSpec with Matchers {
    val sessionKey = new SessionKey

    describe("A SessionKey") {
        it("should be 32 bytes long") {
            sessionKey should have length (32)
        }
    }
}
