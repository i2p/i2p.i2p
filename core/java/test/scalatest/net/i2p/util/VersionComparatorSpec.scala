package net.i2p.util

import org.scalatest.FunSpec
import org.scalatest.matchers.ShouldMatchers

class VersionComparatorSpec extends FunSpec with ShouldMatchers {
    val vc = new VersionComparator

    describe("A VersionComparator") {
        it("should find that 0.1.2 equals 0.1.2") {
            vc.compare("0.1.2", "0.1.2") should equal (0)
        }
        it("should find that 0.1.2 is less than 0.1.3") {
            vc.compare("0.1.2", "0.1.3") should equal (-1)
        }
        it("should find that 0.1.3 is greater than 0.1.2") {
            vc.compare("0.1.3", "0.1.2") should equal (1)
        }
        it("should find that 0.1.2.3.4 is greater than 0.1.2") {
            vc.compare("0.1.2.3.4", "0.1.2") should equal (1)
        }
        it("should find that 0.1.2 is less than 0.1.2.3.4") {
            vc.compare("0.1.2", "0.1.2.3.4") should equal (-1)
        }
        it("should find that 0.1.3 is greater than 0.1.2.3.4") {
            vc.compare("0.1.3", "0.1.2.3.4") should equal (1)
        }
        it("should find that 0.1.2 is equal to 0-1-2") {
            vc.compare("0.1.2", "0-1-2") should equal (0)
        }
        it("should find that 0.1.2 is equal to 0_1_2") {
            vc.compare("0.1.2", "0_1_2") should equal (0)
        }
        it("should find that 0.1.2-foo is equal to 0.1.2-bar") {
            vc.compare("0.1.2-foo", "0.1.2-bar") should equal (0)
        }
        it("should find that -0.1.2 is less than -0.1.3") {
            vc.compare("-0.1.2", "-0.1.3") should equal (-1)
        }
        it("should find that 0..2 is greater than 0.1.2") {
            vc.compare("0..2", "0.1.2") should equal (1)
        }
        it("should find that 0.1.2 is less than 0..2") {
            vc.compare("0.1.2", "0..2") should equal (-1)
        }
    }
}
