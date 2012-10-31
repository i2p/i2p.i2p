package net.i2p.router.update

import org.scalatest.FunSpec

/**
 * @author str4d
 */
trait UpdateRunnerBehaviors { this: FunSpec =>
    def updateRunner(newUpdateRunner: => UpdateRunner) {
        it("should provide a method to run updates") (pending)
    }
}
