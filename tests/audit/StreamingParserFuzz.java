package net.i2p.client.streaming.impl;

import java.util.Random;
import net.i2p.data.ByteArray;

/** Exercise the package-private streaming parser with bounded local inputs. */
public final class StreamingParserFuzz {
    public static void main(String[] args) {
        Random random = new Random(0x4359424552534543L);
        for (int iteration = 0; iteration < 3000; iteration++) {
            byte[] bytes = new byte[32 + random.nextInt(1024)];
            random.nextBytes(bytes);
            int length = random.nextInt(bytes.length + 1);
            if ((iteration & 1) == 0) {
                Packet seed = new Packet(null);
                seed.setSendStreamId(11);
                seed.setReceiveStreamId(22);
                seed.setSequenceNum(iteration);
                seed.setPayload(new ByteArray(new byte[random.nextInt(129)]));
                bytes = new byte[256];
                length = seed.writePacket(bytes, 0);
                if ((iteration & 3) == 0)
                    bytes[random.nextInt(length)] ^= (byte) (1 + random.nextInt(255));
            }
            try {
                new Packet(null).readPacket(bytes, 0, length);
            } catch (IllegalArgumentException | IndexOutOfBoundsException expected) { }
        }
        System.out.println("STREAMING_FUZZ_CASES=3000");
    }
}
