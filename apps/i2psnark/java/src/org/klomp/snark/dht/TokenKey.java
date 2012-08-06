package org.klomp.snark.dht;
/*
 *  GPLv2
 */

import net.i2p.crypto.SHA1Hash;
import net.i2p.data.DataHelper;

/**
 *  Used to index incoming Tokens
 *
 * @since 0.8.4
 * @author zzz
 */
class TokenKey extends SHA1Hash {

    public TokenKey(NID nID, InfoHash ih) {
        super(DataHelper.xor(nID.getData(), ih.getData()));
    }
}
