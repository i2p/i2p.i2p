package net.i2p.router.update

import org.scalatest.FunSpec

import net.i2p.update.Updater

/**
 * @author str4d
 */
trait UpdaterBehaviors { this: FunSpec =>
    def updater(newUpdater: => Updater) {
        it("should provide a method to perform updates") (pending)
    }
}
