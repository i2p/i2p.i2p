package i2p.bote.packet.dht;

import i2p.bote.packet.DataPacket;
import i2p.bote.packet.I2PBotePacket;

import java.io.File;

import net.i2p.data.Hash;
import net.i2p.util.Log;

public abstract class DhtStorablePacket extends DataPacket {
    private static Log log = new Log(DhtStorablePacket.class);

    public abstract Hash getDhtKey();

    /**
     * Creates a {@link DhtStorablePacket} object from its byte array representation.
     * The type of packet depends on the packet type field in the byte array.
     * If there is an error, <code>null</code> is returned.
     * @param data
     * @param log
     * @return
     */
    public static DhtStorablePacket createPacket(byte[] data) {
        DataPacket packet = DataPacket.createPacket(data);
        if (packet instanceof DhtStorablePacket)
            return (DhtStorablePacket)packet;
        else {
            log.error("Packet is not a DhtStorablePacket: " + packet);
            return null;
        }
    }

    public static Class<? extends DhtStorablePacket> decodePacketTypeCode(char packetTypeCode) {
        Class<? extends I2PBotePacket> packetType = I2PBotePacket.decodePacketTypeCode(packetTypeCode);
        if (packetType!=null && DhtStorablePacket.class.isAssignableFrom(packetType))
            return packetType.asSubclass(DhtStorablePacket.class);
        else {
            log.debug("Invalid type code for DhtStorablePacket: <" + packetTypeCode + ">");
            return null;
        }
    }
    
    public static DhtStorablePacket createPacket(File file) {
        DataPacket dataPacket;
        dataPacket = DataPacket.createPacket(file);
        if (dataPacket instanceof DhtStorablePacket)
            return (DhtStorablePacket)dataPacket;
        else {
            log.warn("Expected: DhtStorablePacket, got: " + dataPacket.getClass().getSimpleName());
            return null;
        }
    }
}