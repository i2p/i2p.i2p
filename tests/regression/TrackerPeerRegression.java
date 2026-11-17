package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PClientFactory;
import net.i2p.crypto.SigType;
import net.i2p.data.Destination;
import org.klomp.snark.bencode.BEncoder;
import org.klomp.snark.bencode.BEValue;
import org.klomp.snark.bencode.InvalidBEncodingException;

/** Compact and older non-compact tracker formats remain interoperable. */
public final class TrackerPeerRegression {
    private static int checks;
    public static void main(String[] args) throws Throwable {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        try {
            Destination destination = I2PClientFactory.createClient().createDestination(new ByteArrayOutputStream(), SigType.EdDSA_SHA512_Ed25519);
            Map<String, BEValue> value = new HashMap<String, BEValue>();
            value.put("peer id", new BEValue(new byte[20]));
            value.put("ip", new BEValue(destination.toBase64().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            PeerID peer = PeerID.fromTrackerResponse(value);
            check(Arrays.equals(peer.getDestHash(), destination.calculateHash().getData()) && peer.getID().length == 20,
                    "factory parses old tracker destinations and IDs");
            for (int size : new int[] {0,19,21}) {
                value.put("peer id", new BEValue(new byte[size]));
                rejects(value);
            }
            value.remove("peer id");
            rejects(value);
            value.put("peer id", new BEValue(new byte[20]));
            for (String address : new String[] {"127.0.0.1", "example.com", "not-a-destination"}) {
                value.put("ip", new BEValue(address.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
                rejects(value);
            }
            value.remove("ip");
            rejects(value);
            value.put("ip", new BEValue(destination.toBase64().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            I2PSnarkUtil util = new I2PSnarkUtil(context);
            for (Object peers : new Object[] {List.of(value), destination.calculateHash().getData(),
                    List.of(destination.calculateHash().getData())}) {
                byte[] encoded = BEncoder.bencode(Map.of("interval", Integer.valueOf(1800), "peers", peers));
                TrackerInfo info = new TrackerInfo(new ByteArrayInputStream(encoded), new byte[20], new byte[20], null, util);
                check(info.getPeers().size() == 1, "tracker format yields one peer");
                check(Arrays.equals(info.getPeers().iterator().next().getPeerID().getDestHash(), destination.calculateHash().getData()),
                        "tracker format preserves destination hash");
            }
            Map<String, BEValue> malformed = new HashMap<String, BEValue>(value);
            malformed.put("peer id", new BEValue(new byte[21]));
            byte[] encoded = BEncoder.bencode(Map.of("interval", Integer.valueOf(1800), "peers", List.of(malformed, value)));
            check(new TrackerInfo(new ByteArrayInputStream(encoded), new byte[20], new byte[20], null, util).getPeers().size() == 1,
                    "one malformed legacy entry does not spoil valid peers");
            System.out.println("TRACKER_PEER_CHECKS=" + checks);
        } finally { context.logManager().shutdown(); }
    }
    private static void rejects(Map<String, BEValue> value) throws Exception {
        try { PeerID.fromTrackerResponse(value); throw new AssertionError("bad legacy entry accepted"); }
        catch (InvalidBEncodingException expected) { checks++; }
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
