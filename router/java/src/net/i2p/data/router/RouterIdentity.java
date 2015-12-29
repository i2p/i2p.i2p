package net.i2p.data.router;

import net.i2p.data.Certificate;
import net.i2p.data.KeysAndCert;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Defines the unique identifier of a router, including any certificate or 
 * public key.
 *
 * As of 0.9.9 this data structure is immutable after the two keys and the certificate
 * are set; attempts to change them will throw an IllegalStateException.
 *
 * @since 0.9.16 moved from net.i2p.data
 * @author jrandom
 */
public class RouterIdentity extends KeysAndCert {

    /** 
     * This router specified that they should not be used as a part of a tunnel,
     * nor queried for the netDb, and that disclosure of their contact information
     * should be limited.
     *
     * This is ONLY if the certificate is a hidden type.
     * Hidden mode may also be specified with a capability in the RouterInfo.
     *
     * Not recommended for direct use.
     * Use of RouterInfo.isHidden() (which calls this) is preferred.
     */
    public boolean isHidden() {
        return (_certificate != null) && (_certificate.getCertificateType() == Certificate.CERTIFICATE_TYPE_HIDDEN);
    }

    @Override
    public boolean equals(Object o) {
        return super.equals(o) && (o instanceof RouterIdentity);
    }
}
