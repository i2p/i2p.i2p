package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class UpdateHandlerSpec extends FunSpec with UpdaterBehaviors with MockitoSugar {
    def updateHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val uh = new UpdateHandler(mockCtx, mockMgr)
        uh
    }

    describe("An UpdateHandler") {
        it should behave like updater(updateHandler)
    }
}
