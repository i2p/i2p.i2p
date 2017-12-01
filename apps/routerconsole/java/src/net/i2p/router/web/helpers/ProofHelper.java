package net.i2p.router.web.helpers;

import java.util.Date;

import net.i2p.data.DataHelper;
import net.i2p.data.router.RouterAddress;
import net.i2p.data.router.RouterInfo;
import net.i2p.data.Signature;
import net.i2p.router.web.HelperBase;

/**
 *  Sign a statement about this router.
 *  @since 0.9.8
 */
public class ProofHelper extends HelperBase {
    
    public String getProof() {
        StringBuilder buf = new StringBuilder(512);
        RouterInfo us = _context.router().getRouterInfo();
        buf.append("Hash: ").append(us.getIdentity().calculateHash().toBase64()).append('\n');
        //buf.append("Ident: ").append(us.getIdentity().toBase64()).append('\n');
        for (RouterAddress addr : us.getAddresses()) {
            buf.append(addr.getTransportStyle()).append(": ").append(addr.getHost()).append('\n');
        }
        buf.append("Caps: ").append(us.getCapabilities()).append('\n');
        buf.append("Date: ").append(new Date()); // no trailing newline
        String msg = buf.toString();
        byte[] data = DataHelper.getUTF8(msg);
        Signature sig = _context.dsa().sign(data, _context.keyManager().getSigningPrivateKey());
        buf.setLength(0);
        buf.append("---BEGIN I2P SIGNED MESSAGE---\n");
        buf.append(msg);
        buf.append("\n---BEGIN I2P SIGNATURE---\n");
        buf.append(sig.toBase64());
        buf.append("\n---END I2P SIGNATURE---");
        return buf.toString();
    }
}
