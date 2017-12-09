package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mockito.MockitoSugar

import net.i2p.app.ClientAppManager;
import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class ConsoleUpdateManagerSpec extends FunSpec with UpdateManagerBehaviors with MockitoSugar {
    def consoleUpdateManager = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ClientAppManager]
        val cum = new ConsoleUpdateManager(mockCtx, mockMgr, null)
        cum
    }

    describe("A ConsoleUpdateManager") {
        it should behave like updateManager(consoleUpdateManager)
    }
}
