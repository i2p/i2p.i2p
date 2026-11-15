package org.klomp.snark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import org.klomp.snark.bencode.BEValue;

/** Exercise actual framing and request handling without I2P sockets. */
public final class PeerFrameRegression {
    private static int checks;

    public static void main(String[] args) throws Throwable {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        try {
            for (int[] rule : new int[][] {{0,1}, {1,1}, {2,1}, {3,1}, {4,5}, {6,13}, {8,13},
                    {9,3}, {13,5}, {14,1}, {15,1}, {16,13}, {17,5}}) {
                for (int length : new int[] {Math.max(1, rule[1] - 1), rule[1] + 1}) {
                    if (length == rule[1]) continue;
                    malformed(rule[0], length);
                }
                valid(rule[0], new byte[rule[1] - 1], true);
            }
            for (int length = 1; length <= 9; length++) malformed(Message.PIECE, length);
            malformed(Message.EXTENSION, 1);
            valid(Message.EXTENSION, new byte[] {5}, true);
            valid(Message.BITFIELD, new byte[] {1, 2, 3}, true);
            valid(99, new byte[] {1, 2, 3}, true);
            valid(Message.PIECE, new byte[9], false);
            requestRanges();
            System.out.println("PEER_FRAME_CHECKS=" + checks);
        } finally { context.logManager().shutdown(); }
    }

    private static void malformed(int type, int length) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(length);
        out.writeByte(type);
        out.write(new byte[24]); // bytes must not be interpreted as frame fields
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        TestPeer peer = new TestPeer();
        RecordingState state = new RecordingState(peer);
        peer.state = state;
        new PeerConnectionIn(peer, new DataInputStream(input)).run();
        check(state.calls == 0 && input.available() == 24 && peer.disconnected,
                "reject type " + type + " length " + length + " before reading the next frame");
    }

    private static void valid(int type, byte[] payload, boolean callback) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1 + payload.length);
        out.writeByte(type);
        out.write(payload);
        out.writeInt(0); // following keepalive verifies alignment
        ByteArrayInputStream input = new ByteArrayInputStream(bytes.toByteArray());
        TestPeer peer = new TestPeer();
        RecordingState state = new RecordingState(peer);
        peer.state = state;
        new PeerConnectionIn(peer, new DataInputStream(input)).run();
        check(input.available() == 0 && state.calls == (callback ? 2 : 1) && state.alive == 1,
                "valid type " + type + " retains frame alignment");
    }

    private static void requestRanges() throws Exception {
        Map<String, BEValue> info = new HashMap<String, BEValue>();
        info.put("name", new BEValue("range-test".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        info.put("piece length", new BEValue(16384));
        info.put("length", new BEValue(17000));
        info.put("pieces", new BEValue(new byte[40]));
        MetaInfo meta = new MetaInfo(Map.of("info", new BEValue(info)));
        TestPeer peer = new TestPeer();
        RecordingOut out = new RecordingOut(peer);
        PeerState state = new PeerState(peer, null, null, meta, null, out);
        state.choking = false;
        for (int[] request : new int[][] {{0,0,16384}, {0,16383,1}, {1,0,616}, {1,615,1}})
            state.requestMessage(request[0], request[1], request[2]);
        check(out.pieces == 4, "valid requests including final short piece accepted");
        for (int[] request : new int[][] {{0,16383,2}, {0,16384,1}, {1,0,617}, {1,615,2},
                {1,Integer.MAX_VALUE,1}, {0,1,Integer.MAX_VALUE}, {-1,0,1}, {2,0,1}, {0,-1,1}, {0,0,0}})
            state.requestMessage(request[0], request[1], request[2]);
        check(out.pieces == 4, "out-of-piece and overflowing requests never reach the data loader");
    }

    private static final class TestPeer extends Peer {
        boolean disconnected;
        TestPeer() throws Exception { super(new PeerID(new byte[32], (I2PSnarkUtil) null), new byte[20], new byte[20], null); }
        @Override public void disconnect() { disconnected = true; }
    }

    private static final class RecordingOut extends PeerConnectionOut {
        int pieces;
        RecordingOut(Peer peer) { super(peer, new DataOutputStream(new ByteArrayOutputStream())); }
        @Override void sendPiece(int piece, int begin, int length, DataLoader loader) { pieces++; }
    }

    private static final class RecordingState extends PeerState {
        int calls, alive;
        RecordingState(Peer peer) { super(peer, null, null, null, null, null); }
        @Override void keepAliveMessage() { calls++; alive++; }
        @Override void chokeMessage(boolean value) { calls++; }
        @Override void interestedMessage(boolean value) { calls++; }
        @Override void haveMessage(int piece) { calls++; }
        @Override void haveMessage(boolean all) { calls++; }
        @Override void bitfieldMessage(byte[] bytes) { calls++; }
        @Override void requestMessage(int piece, int begin, int length) { calls++; }
        @Override void cancelMessage(int piece, int begin, int length) { calls++; }
        @Override void portMessage(int port) { calls++; }
        @Override void extensionMessage(int id, byte[] bytes) { calls++; }
        @Override void suggestMessage(int piece) { calls++; }
        @Override void rejectMessage(int piece, int begin, int length) { calls++; }
        @Override void allowedFastMessage(int piece) { calls++; }
        @Override void unknownMessage(int type, byte[] bytes) { calls++; }
        @Override Request getOutstandingRequest(int piece, int begin, int length) { return null; }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
}
