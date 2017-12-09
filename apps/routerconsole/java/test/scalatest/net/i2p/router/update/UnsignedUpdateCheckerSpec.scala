package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mockito.MockitoSugar

import java.net.URI
import java.util.Collections

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class UnsignedUpdateCheckerSpec extends FunSpec with UpdateRunnerBehaviors with MockitoSugar {
    def unsignedUpdateChecker = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val mockUri = mock[URI]
        val uris = Collections.singletonList(mockUri)
        val uuc = new UnsignedUpdateChecker(mockCtx, mockMgr, uris, 0)
        uuc
    }

    describe("An UnsignedUpdateChecker") {
        it should behave like updateRunner(unsignedUpdateChecker)
    }
}
