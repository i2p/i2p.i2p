package net.i2p.router.update

import org.scalatest.FunSpec
import org.scalatest.mockito.MockitoSugar

import java.net.URI
import java.util.Collections

import net.i2p.router.RouterContext

/**
 * @author str4d
 */
class NewsFetcherSpec extends FunSpec with UpdateRunnerBehaviors with MockitoSugar {
    def newsFetcher = {
        val mockCtx = mock[RouterContext]
        val mockMgr = mock[ConsoleUpdateManager]
        val mockUri = mock[URI]
        val uris = Collections.singletonList(mockUri)
        val nf = new NewsFetcher(mockCtx, mockMgr, uris)
        nf
    }

    describe("A NewsFetcher") {
        it should behave like updateRunner(newsFetcher)
    }
}
