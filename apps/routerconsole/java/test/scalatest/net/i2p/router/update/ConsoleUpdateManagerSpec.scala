package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class ConsoleUpdateManagerSpec extends FunSpec with UpdateManagerBehaviors with MockitoSugar {
    def consoleUpdateManager = {
        val mockCtx = mock[RouterContext]
        val cum = new ConsoleUpdateManager(mockCtx)
        cum
    }

    describe("A ConsoleUpdateManager") {
        it should behave like updateManager(consoleUpdateManager)
    }
}
