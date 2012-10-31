package net.i2p.router.update

import org.scalatest.FunSpec

import net.i2p.update.Checker

/**
 * @author str4d
 */
trait CheckerBehaviors { this: FunSpec =>
    def checker(newChecker: => Checker) {
        it("should provide a method to check for updates") (pending)
    }
}
