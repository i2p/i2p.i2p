package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class NewsHandlerSpec extends FunSpec with UpdaterBehaviors with MockitoSugar {
    def newsHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val nh = new NewsHandler(mockCtx, mockMgr)
        nh
    }

    describe("A NewsHandler") {
        it should behave like updater(newsHandler)
    }
}
