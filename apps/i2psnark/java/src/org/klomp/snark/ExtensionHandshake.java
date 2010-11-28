package org.klomp.snark;

import java.util.HashMap;
import java.util.Map;

import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;

/**
 * REF: BEP 10 Extension Protocol
 * @since 0.8.2
 */
class ExtensionHandshake {

    private static final byte[] _payload = buildPayload();

  /**
   *  @return bencoded data
   */
    static byte[] getPayload() {
        return _payload;
    }

    /** just a test for now */
    private static byte[] buildPayload() {
        Map<String, Object> handshake = new HashMap();
        Map<String, Integer> m = new HashMap();
        m.put("foo", Integer.valueOf(99));
        m.put("bar", Integer.valueOf(101));
        handshake.put("m", m);
        handshake.put("p", Integer.valueOf(6881));
        handshake.put("v", "I2PSnark");
        handshake.put("reqq", Integer.valueOf(5));
        return BEncoder.bencode(handshake);
    }
}
