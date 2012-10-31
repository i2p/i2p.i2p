package net.i2p.router.update

import org.scalatest.FunSpec

import net.i2p.update.UpdateManager

/**
 * @author str4d
 */
trait UpdateManagerBehaviors { this: FunSpec =>
    def updateManager(newUpdateManager: => UpdateManager) {
        it("should provide a method to register updaters") (pending)

        it("should provide a method to unregister updaters") (pending)

        it("should provide a method to register checkers") (pending)

        it("should provide a method to unregister checkers") (pending)

        it("should provide a start method") (pending)

        it("should provide a shutdown method") (pending)

        it("should notify when a new version is available") (pending)

        it("should notify when a check is complete") (pending)

        it("should provide a method to notify progress") (pending)

        it("should provide a method to notify progress with completion status") (pending)

        it("should notify when a single update attempt fails") (pending)

        it("should notify when an entire task finishes and has failed") (pending)

        it("should notify when an update has been downloaded, and verify it") (pending)

        it("should notify when") (pending)
    }
}
