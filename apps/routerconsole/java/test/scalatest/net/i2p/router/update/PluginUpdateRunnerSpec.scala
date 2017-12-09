package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mockito.MockitoSugar

import java.net.URI
import java.util.Collections

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class PluginUpdateRunnerSpec extends FunSpec with UpdateRunnerBehaviors with MockitoSugar {
    def pluginUpdateRunner = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val mockUri = mock[URI]
        val uris = Collections.singletonList(mockUri)
        val pur = new PluginUpdateRunner(mockCtx, mockMgr, uris, "appName", "appVersion")
        pur
    }

    describe("A PluginUpdateRunner") {
        it should behave like updateRunner(pluginUpdateRunner)
    }
}
