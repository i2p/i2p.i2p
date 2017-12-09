package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mockito.MockitoSugar

import java.net.URI
import java.util.Collections

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class UnsignedUpdateRunnerSpec extends FunSpec with UpdateRunnerBehaviors with MockitoSugar {
    def unsignedUpdateRunner = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val mockUri = mock[URI]
        val uris = Collections.singletonList(mockUri)
        val uur = new UnsignedUpdateRunner(mockCtx, mockMgr, uris)
        uur
    }

    describe("An UnsignedUpdateRunner") {
        it should behave like updateRunner(unsignedUpdateRunner)
    }
}
