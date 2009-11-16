package i2p.bote.packet;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.i2p.util.Log;

public abstract class CommunicationPacket extends I2PBotePacket {
    private static final byte PACKET_VERSION = 1;
    protected static final int HEADER_LENGTH = 6 + UniqueId.LENGTH;   // length of the common packet header in the byte array representation; this is where subclasses start reading
    private static final byte[] PACKET_PREFIX = new byte[] {(byte)0x6D, (byte)0x30, (byte)0x52, (byte)0xE9};
    private static Log static_log = new Log(CommunicationPacket.class);
    
    private Log log = new Log(CommunicationPacket.class);
    private UniqueId packetId;
    private CountDownLatch sentSignal;
    private long sentTime;

    protected CommunicationPacket() {
        this(new UniqueId());
    }
    
    protected CommunicationPacket(UniqueId packetId) {
        this.packetId = packetId;
        sentSignal = new CountDownLatch(1);
        sentTime = -1;
    }
    
    /**
     * Creates a packet and initializes the header fields shared by all Communication Packets: packet type and packet id.
     * @param data
     */
    protected CommunicationPacket(byte[] data) {
        verifyHeader(data);
        checkPacketType(data[4]);
        packetId = new UniqueId(data, 6);
    }
    
    /**
     * Creates a packet object from its byte array representation. If there is an error,
     * <code>null</code> is returned.
     * @param data
     * @param log
     * @return
     */
    public static CommunicationPacket createPacket(byte[] data) {
        char packetTypeCode = (char)data[4];   // byte 4 of a communication packet is the packet type code
        Class<? extends I2PBotePacket> packetType = decodePacketTypeCode(packetTypeCode);
        if (packetType==null || !CommunicationPacket.class.isAssignableFrom(packetType)) {
            static_log.error("Type code is not a CommunicationPacket type code: <" + packetTypeCode + ">");
            return null;
        }
        
        Class<? extends CommunicationPacket> commPacketType = packetType.asSubclass(CommunicationPacket.class);
        try {
            return commPacketType.getConstructor(byte[].class).newInstance(data);
        }
        catch (Exception e) {
            static_log.warn("Can't instantiate packet for type code <" + packetTypeCode + ">", e);
            return null;
        }
    }
    
    // check the packet prefix and version number of a packet
    private void verifyHeader(byte[] packet) {
        for (int i=0; i<PACKET_PREFIX.length; i++)
            if (packet[i] != PACKET_PREFIX[i])
                log.error("Packet prefix invalid at byte " + i + ". Expected = " + PACKET_PREFIX[i] + ", actual = " + packet[i]);
        if (packet[5] != 1)
            log.error("Unsupported packet version: " + packet[5]);
    }

    public void setPacketId(UniqueId packetId) {
        this.packetId = packetId;
    }
    
    public UniqueId getPacketId() {
        return packetId;
    }
    
    public synchronized void setSentTime(long sentTime) {
        this.sentTime = sentTime;
        sentSignal.countDown();
    }

    public synchronized long getSentTime() {
        return sentTime;
    }

    public boolean hasBeenSent() {
        return sentTime > 0;
    }
    
    public boolean awaitSending(long timeout, TimeUnit unit) throws InterruptedException {
        return sentSignal.await(timeout, unit);
    }
    
    /**
     * Writes the Prefix, Version, Type, and Packet Id fields of a I2PBote packet to
     * an {@link OutputStream}.
     * @param outputStream
     */
    protected void writeHeader(OutputStream outputStream) throws IOException {
        outputStream.write(PACKET_PREFIX);
        outputStream.write((byte)getPacketTypeCode());
        outputStream.write(PACKET_VERSION);
        outputStream.write(packetId.toByteArray());
    }
    
    @Override
    public String toString() {
        return "Type=" + getClass().getSimpleName() + " Id=" + (packetId==null?"<null>":packetId.toString().substring(0, 8)) + "...";
    }
}