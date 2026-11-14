package net.i2p.router.transport.ntcp;

import java.io.IOException;
import java.util.Random;
import java.util.Arrays;
import net.i2p.I2PAppContext;
import net.i2p.data.DataFormatException;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.I2NPMessageException;
import net.i2p.data.router.RouterInfo;

/** Deterministic, bounded local mutation checks. Never opens a network socket. */
public final class ParserFuzz implements NTCP2Payload.PayloadCallback {
    public static void main(String[] args) throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        context.logManager().setDefaultLimit("CRIT");
        Random random = new Random(0x4359424552534543L);
        ParserFuzz callback = new ParserFuzz();
        int ntcp = 0;
        for (int iteration = 0; iteration < 3000; iteration++) {
            byte[] bytes = new byte[32 + random.nextInt(1024)];
            random.nextBytes(bytes);
            int length = random.nextInt(bytes.length + 1);
            if ((iteration & 1) == 0) {
                NTCP2Payload.OptionsBlock options = new NTCP2Payload.OptionsBlock(new byte[random.nextInt(65)]);
                NTCP2Payload.PaddingBlock padding = new NTCP2Payload.PaddingBlock(random.nextInt(17));
                length = options.getTotalLength() + padding.getTotalLength();
                bytes = new byte[length];
                NTCP2Payload.writePayload(bytes, 0, Arrays.asList(options, padding));
                if ((iteration & 3) == 0)
                    bytes[random.nextInt(length)] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                NTCP2Payload.processPayload(context, callback, bytes, 0, length, false);
            } catch (IOException | DataFormatException | I2NPMessageException expected) { }
            ntcp++;

        }
        System.out.println("NTCP_FUZZ_CASES=" + ntcp);
        context.logManager().shutdown();
    }
    public void gotDateTime(long time) { }
    public void gotI2NP(I2NPMessage message) { }
    public void gotOptions(byte[] options, boolean handshake) { }
    public void gotRI(RouterInfo info, boolean handshake, boolean flood) { }
    public void gotTermination(int reason, long received) { }
    public void gotPadding(int length, int frameLength) { }
    public void gotUnknown(int type, int length) { }
}
