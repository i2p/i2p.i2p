package net.i2p.data

import org.scalatest.FunSpec
import org.scalatest.Matchers

/**
 * @author str4d
 */
class SessionTagSpec extends FunSpec with Matchers {
    val sessionTag = new SessionTag

    describe("A SessionTag") {
        it("should be 32 bytes long") {
            sessionTag should have length (32)
        }
    }
}
