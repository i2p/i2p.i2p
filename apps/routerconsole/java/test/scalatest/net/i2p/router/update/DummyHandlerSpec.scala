package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class DummyHandlerSpec extends FunSpec with CheckerBehaviors with UpdaterBehaviors with MockitoSugar {
    def dummyHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val dh = new DummyHandler(mockCtx, mockMgr)
        dh
    }

    describe("A DummyHandler") {
        it should behave like checker(dummyHandler)

        it should behave like updater(dummyHandler)
    }
}
