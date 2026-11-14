package net.i2p.client.streaming.impl;

import java.util.Arrays;
import net.i2p.data.ByteArray;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;

public final class PacketOffsetRegression {
    public static void main(String[] args) {
        int failures = 0;
        int checks = 0;
        for (boolean from : new boolean[] {false, true}) {
            for (int size : new int[] {0, 1, 20, 256}) {
                Packet sent = new Packet(null);
                sent.setSendStreamId(11);
                sent.setReceiveStreamId(22);
                sent.setSequenceNum(33);
                sent.setAckThrough(7);
                sent.setNacks(new long[] {2, 4});
                byte[] data = new byte[size];
                for (int i = 0; i < size; i++) data[i] = (byte) i;
                sent.setPayload(new ByteArray(data));
                if (from) {
                    Destination destination = new Destination();
                    destination.setPublicKey(new PublicKey(new byte[256]));
                    destination.setSigningPublicKey(new SigningPublicKey(new byte[128]));
                    destination.setCertificate(new Certificate(Certificate.CERTIFICATE_TYPE_NULL, null));
                    sent._optionFrom = destination;
                    sent.setFlag(Packet.FLAG_FROM_INCLUDED);
                }
                byte[] wire = new byte[2048];
                int length = sent.writePacket(wire, 0);
                for (int offset : new int[] {0, 1, 17, 1000}) {
                    checks++;
                    byte[] padded = new byte[length + offset + 16];
                    Arrays.fill(padded, (byte) 0x55);
                    System.arraycopy(wire, 0, padded, offset, length);
                    try {
                        Packet parsed = new Packet(null);
                        parsed.readPacket(padded, offset, length);
                        ByteArray payload = parsed.getPayload();
                        byte[] actual = Arrays.copyOfRange(payload.getData(), payload.getOffset(),
                                                          payload.getOffset() + payload.getValid());
                        if (parsed.getSendStreamId() != 11 || parsed.getReceiveStreamId() != 22
                            || parsed.getSequenceNum() != 33 || parsed.getAckThrough() != 7
                            || !Arrays.equals(parsed.getNacks(), new long[] {2, 4})
                            || !Arrays.equals(actual, data) || (from && parsed.getOptionalFrom() == null))
                            throw new AssertionError("parsed fields differ");
                    } catch (Exception | AssertionError failure) {
                        failures++;
                        System.out.println("FAIL: offset=" + offset + ", payload=" + size + ", FROM=" + from
                                           + ": " + failure.getClass().getSimpleName());
                    }
                }
            }
        }
        System.out.println("CHECKS=" + checks + "; FAILURES=" + failures);
        if (failures != 0) System.exit(1);
    }
}
