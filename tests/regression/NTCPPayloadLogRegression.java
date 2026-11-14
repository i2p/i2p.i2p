package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.i2p.I2PAppContext;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.router.RouterInfo;

public final class NTCPPayloadLogRegression implements NTCP2Payload.PayloadCallback {
    public static void main(String[] args) throws Exception {
        byte[] frame = new byte[128];
        frame[0] = 1;
        frame[1] = 1; // Declares 256 bytes in a 128-byte frame.
        System.arraycopy("SECRETMSG".getBytes(StandardCharsets.US_ASCII), 0, frame, 3, 9);
        try {
            NTCP2Payload.processPayload(I2PAppContext.getGlobalContext(), new NTCPPayloadLogRegression(),
                                       frame, 0, frame.length, false);
            throw new AssertionError("oversized block accepted");
        } catch (IOException expected) {
            if (expected.getMessage().contains("SECRETMSG"))
                throw new AssertionError("decrypted payload present in exception");
            if (!expected.getMessage().contains("frame of size 128"))
                throw new AssertionError("frame size diagnostic missing");
        }
        System.out.println("CHECKS=1; FAILURES=0");
    }
    public void gotDateTime(long time) { }
    public void gotI2NP(I2NPMessage message) { }
    public void gotOptions(byte[] options, boolean handshake) { }
    public void gotRI(RouterInfo info, boolean handshake, boolean flood) { }
    public void gotTermination(int reason, long received) { }
    public void gotPadding(int length, int frameLength) { }
    public void gotUnknown(int type, int length) { }
}
