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
class UpdateHandlerSpec extends FunSpec with UpdaterBehaviors with MockitoSugar {
    def updateHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val uh = new UpdateHandler(mockCtx, mockMgr)
        uh
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

    describe("An UpdateHandler") {
        it should behave like updater(updateHandler, validTypes, validMethods)
    }
}
