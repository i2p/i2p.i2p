package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mock.MockitoSugar

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class PluginUpdateHandlerSpec extends FunSpec with CheckerBehaviors with UpdaterBehaviors with MockitoSugar {
    def pluginUpdateHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val puh = new PluginUpdateHandler(mockCtx, mockMgr)
        puh
    }

    describe("A PluginUpdateHandler") {
        it should behave like checker(pluginUpdateHandler)

        it should behave like updater(pluginUpdateHandler)
    }
}
