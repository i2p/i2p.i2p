package net.i2p.router.web;

import java.io.Writer;
import java.util.Iterator;
import java.util.Set;

import net.i2p.data.Hash;
import net.i2p.router.RouterContext;

/**
 * uuuugly.  dump the peer profile data if given a peer.
 *
 */
public class StatHelper extends HelperBase {
    private String _peer;
    
    public void setPeer(String peer) { _peer = peer; }
    
    public String getProfile() { 
        RouterContext ctx = (RouterContext)net.i2p.router.RouterContext.listContexts().get(0);
        Set peers = ctx.profileOrganizer().selectAllPeers();
        for (Iterator iter = peers.iterator(); iter.hasNext(); ) {
            Hash peer = (Hash)iter.next();
            if (peer.toBase64().startsWith(_peer)) {
                try {
                    WriterOutputStream wos = new WriterOutputStream(_out);
                    ctx.profileOrganizer().exportProfile(peer, wos);
                    wos.flush();
                    _out.flush();
                    return "";
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return "Unknown";
    }
}
