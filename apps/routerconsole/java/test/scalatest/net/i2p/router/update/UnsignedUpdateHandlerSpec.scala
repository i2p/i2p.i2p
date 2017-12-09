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
class UnsignedUpdateHandlerSpec extends FunSpec with CheckerBehaviors with UpdaterBehaviors with MockitoSugar {
    def unsignedUpdateHandler = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val uuh = new UnsignedUpdateHandler(mockCtx, mockMgr)
        uuh
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

    describe("An UnsignedUpdateHandler") {
        it should behave like checker(unsignedUpdateHandler)

        it should behave like updater(unsignedUpdateHandler, validTypes, validMethods)
    }
}
