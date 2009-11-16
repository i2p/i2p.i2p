package i2p.bote.network;

import i2p.bote.packet.I2PBotePacket;

public interface SendQueuePacketListener {

    /**
     * 
     * @param packet
     */
    void packetSent(I2PBotePacket packet);
}