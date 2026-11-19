package net.i2p.i2ptunnel;

import junit.framework.TestCase;

/**
 *  Unit tests for the cross-network (I2P &lt;-&gt; clearnet) sub-resource block
 *  that defends against favicon/resource-based tracking and correlation attacks.
 *
 *  @since 0.9.67
 */
public class CrossNetworkBlockTest extends TestCase {

    private static final boolean CLEARNET = true;
    private static final boolean I2P = false;

    // ---- the core leak: an I2P page pulling a clearnet sub-resource ----

    public void testI2PPageLoadingClearnetFaviconIsBlocked() {
        // <link rel="icon" href="http://tracker.example.com/id.ico"> on an eepsite
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", "image", "no-cors", CLEARNET));
    }

    public void testI2PPageLoadingClearnetImageIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "image", "no-cors", CLEARNET));
    }

    public void testI2PPageLoadingClearnetScriptIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "script", "no-cors", CLEARNET));
    }

    public void testB32I2PRefererIsRecognised() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://ukeu3k5oycgaauneqgtnvselmt4yemvoilkln7jpvamvfx7dnkdq.b32.i2p/", "image", "no-cors", CLEARNET));
    }

    // ---- the reverse direction: a clearnet page probing an I2P resource ----

    public void testClearnetPageLoadingI2PResourceIsBlocked() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://example.com/page", "image", "no-cors", I2P));
    }

    // ---- top-level navigation is always allowed ----

    public void testTopLevelNavigationToClearnetFromI2PAllowed() {
        // user clicked a clearnet link on an eepsite
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "document", "navigate", CLEARNET));
    }

    public void testNavigateModeBeatsSubresourceDest() {
        // mode=navigate is authoritative even if dest looks resource-like
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "empty", "navigate", CLEARNET));
    }

    public void testNestedNavigateAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "iframe", "nested-navigate", CLEARNET));
    }

    public void testDocumentDestWithoutModeAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://forum.i2p/thread", "document", null, CLEARNET));
    }

    // ---- same-network loads are always allowed ----

    public void testI2PPageLoadingI2PFaviconAllowed() {
        // a site's own favicon, same network
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", "image", "no-cors", I2P));
    }

    public void testI2PPageLoadingOtherEepsiteResourceAllowed() {
        // cross-site but same network stays inside I2P, no clearnet leak
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://a.i2p/page", "image", "no-cors", I2P));
    }

    public void testClearnetPageLoadingClearnetResourceAllowed() {
        // normal outproxy browsing of a clearnet site with a clearnet CDN
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://example.com/page", "image", "no-cors", CLEARNET));
    }

    // ---- requests that do not reveal a cross-network origin are allowed ----

    public void testNoRefererAllowed() {
        // cannot prove cross-network without an origin; do not break direct loads
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                null, "image", "no-cors", CLEARNET));
    }

    public void testRelativeRefererAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "/page", "image", "no-cors", CLEARNET));
    }

    public void testMalformedRefererAllowed() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "::not a uri::", "image", "no-cors", CLEARNET));
    }

    // ---- Referer-only fallback (older browsers that omit Fetch Metadata) ----

    public void testCrossNetworkBlockedWithoutSecFetch() {
        assertTrue(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, CLEARNET));
    }

    public void testSameNetworkAllowedWithoutSecFetch() {
        assertFalse(I2PTunnelHTTPClient.isCrossNetworkResourceRequest(
                "http://secure.i2p/page", null, null, I2P));
    }
}
