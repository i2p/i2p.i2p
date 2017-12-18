package net.i2p.router.web.helpers;

import java.io.IOException;
import java.util.Set;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.web.HelperBase;
import net.i2p.servlet.util.WriterOutputStream;

/**
 *  Dump the peer profile data if given a full B64 peer string or prefix.
 *
 */
public class StatHelper extends HelperBase {
    private String _peer;
    
    /**
     * Caller should strip HTML (XSS)
     */
    public void setPeer(String peer) { _peer = peer; }
    
    /**
     *  Look up based on a b64 prefix or full b64.
     *  Prefix is inefficient.
     */
    public String getProfile() { 
        if (_peer == null || _peer.length() <= 0)
            return "No peer specified";
        if (_peer.length() >= 44)
            return outputProfile();
        Set<Hash> peers = _context.profileOrganizer().selectAllPeers();
        for (Hash peer : peers) {
            if (peer.toBase64().startsWith(_peer)) {
                return dumpProfile(peer);
            }
        }
        return "Unknown peer " + _peer;
    }

    /**
     *  Look up based on the full b64 - efficient
     *  @since 0.8.5
     */
    private String outputProfile() { 
        Hash peer = new Hash();
        try {
            peer.fromBase64(_peer);
            return dumpProfile(peer);
        } catch (DataFormatException dfe) {
            return "Bad peer hash " + _peer;
        }
    }

    /**
     *  dump the profile
     *  @since 0.8.5
     */
    private String dumpProfile(Hash peer) { 
        try {
            WriterOutputStream wos = new WriterOutputStream(_out);
            boolean success = _context.profileOrganizer().exportProfile(peer, wos);
            if (success) {
                wos.flush();
                _out.flush();
                return "";
            } else {
                return "Unknown peer " + _peer;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return "IO Error " + e;
        }
    }
}
