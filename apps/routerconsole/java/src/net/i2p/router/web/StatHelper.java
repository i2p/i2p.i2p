package net.i2p.router.web;

/**
 * uuuugly.  dump the peer profile data if given a peer.
 *
 */
public class StatHelper {
    private String _peer;
    public void setPeer(String peer) { _peer = peer; }
    public String getProfile() { 
        net.i2p.router.RouterContext ctx = (net.i2p.router.RouterContext)net.i2p.router.RouterContext.listContexts().get(0);
        java.util.Set peers = ctx.profileOrganizer().selectAllPeers();
        for (java.util.Iterator iter = peers.iterator(); iter.hasNext(); ) {
            net.i2p.data.Hash peer = (net.i2p.data.Hash)iter.next();
            if (_peer.indexOf(peer.toBase64().substring(0,10)) >= 0) {
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(64*1024);
                    ctx.profileOrganizer().exportProfile(peer, baos);
                    return new String(baos.toByteArray());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return "Unknown";
    }
}
