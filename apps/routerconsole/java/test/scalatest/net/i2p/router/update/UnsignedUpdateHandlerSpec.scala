package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class UnsignedUpdateHandlerSpec extends FunSpec with CheckerBehaviors with UpdaterBehaviors with MockitoSugar {
    def unsignedUpdateHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val uuh = new UnsignedUpdateHandler(mockCtx, mockMgr)
        uuh
    }

    describe("An UnsignedUpdateHandler") {
        it should behave like checker(unsignedUpdateHandler)

        it should behave like updater(unsignedUpdateHandler)
    }
}
