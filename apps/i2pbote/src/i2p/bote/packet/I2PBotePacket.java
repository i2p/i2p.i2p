package i2p.bote.packet;

import java.nio.ByteBuffer;

import i2p.bote.packet.dht.FindClosePeersPacket;
import i2p.bote.packet.dht.RetrieveRequest;
import i2p.bote.packet.dht.StoreRequest;
import net.i2p.data.Hash;
import net.i2p.util.Log;

public abstract class I2PBotePacket {
    private static final int MAX_DATAGRAM_SIZE = 31 * 1024;
    private static final Log log = new Log(I2PBotePacket.class);
    @SuppressWarnings("unchecked")
    private static Class<? extends I2PBotePacket>[] ALL_PACKET_TYPES = new Class[] {
        RelayPacket.class, ResponsePacket.class, RetrieveRequest.class, StoreRequest.class, FindClosePeersPacket.class,
        PeerList.class, EmailPacket.class, IndexPacket.class
    };
    
	public abstract byte[] toByteArray();
	
	/**
	 * Returns the size of the packet in bytes.
	 * @return
	 */
	public int getSize() {
	    return toByteArray().length;
	}
    
    /**
     * Returns <code>false</code> if this packet can't fit into an I2P datagram.
     * @return
     */
    public boolean isTooBig() {
        return getSize() > MAX_DATAGRAM_SIZE;
    }
    
    protected char getPacketTypeCode(Class<? extends I2PBotePacket> dataType) {
        return dataType.getAnnotation(TypeCode.class).value();
    }
	
    public char getPacketTypeCode() {
        return getPacketTypeCode(getClass());
    }

	/**
	 * Logs an error if the packet type of the packet instance is not correct
	 * @param packetTypeCode
	 */
	protected void checkPacketType(char packetTypeCode) {
	    if (getPacketTypeCode() != packetTypeCode)
	        log.error("Packet type code of class " + getClass().getSimpleName() + " should be " + getPacketTypeCode() + ", is <" + packetTypeCode + ">");
	}
	
    protected void checkPacketType(byte packetTypeCode) {
        checkPacketType((char)packetTypeCode);
    }

/*	protected void checkPacketVersion(byte version, byte minVersion, byte maxVersion) {
	    // TODO
	}*/
    
    /**
     * Creates a {@link Hash} from bytes read from a {@link ByteBuffer}.
     * No check is done to make sure the buffer has enough bytes available.
     */
    protected Hash readHash(ByteBuffer buffer) {
        byte[] bytes = new byte[Hash.HASH_LENGTH];
        buffer.get(bytes);
        return new Hash(bytes);
    }
	
    protected static Class<? extends I2PBotePacket> decodePacketTypeCode(char packetTypeCode) {
        for (Class<? extends I2PBotePacket> packetType: ALL_PACKET_TYPES)
            if (packetType.getAnnotation(TypeCode.class).value() == packetTypeCode)
                return packetType;
        
        log.debug("Invalid type code for I2PBotePacket: <" + packetTypeCode + ">");
        return null;
    }
    
}