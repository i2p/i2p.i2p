package net.i2p.data;

import org.scalatest.FunSpec
import org.scalatest.matchers.ShouldMatchers

/**
 * @author str4d
 */
class HashSpec extends FunSpec with ShouldMatchers {
    val hash = new Hash

    describe("A Hash") {
        it("should be 32 bytes long") {
            hash.length should be (32)
        }
    }
}
