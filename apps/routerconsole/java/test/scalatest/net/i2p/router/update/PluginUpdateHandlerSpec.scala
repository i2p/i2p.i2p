package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mockito.MockitoSugar

import java.util.ArrayList

import net.i2p.router.RouterContext
import net.i2p.update.UpdateMethod
import net.i2p.update.UpdateType

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

    def validTypes = {
        val vt = new ArrayList[UpdateType]
        vt.add(UpdateType.ROUTER_UNSIGNED)
        vt
    }

    def validMethods = {
        val vm = new ArrayList[UpdateMethod]
        vm.add(UpdateMethod.HTTP)
        vm
    }

    describe("A PluginUpdateHandler") {
        it should behave like checker(pluginUpdateHandler)

        it should behave like updater(pluginUpdateHandler, validTypes, validMethods)
    }
}
