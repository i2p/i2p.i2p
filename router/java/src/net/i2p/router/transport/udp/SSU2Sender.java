package net.i2p.router.transport.udp;

import java.net.InetAddress;
import java.util.List;

import com.southernstorm.noise.protocol.CipherState;

/**
 * Basic interface over top of PeerState2 and PeerStateDestroyed,
 * so we can pass them both to PacketBuilder2 to send packets.
 *
 * @since 0.9.57
 */
interface SSU2Sender {
    RemoteHostId getRemoteHostId();
    boolean isIPv6();
    InetAddress getRemoteIPAddress();
    int getRemotePort();
    int getMTU();
    long getNextPacketNumber();
    long getSendConnID();
    CipherState getSendCipher();
    byte[] getSendHeaderEncryptKey1();
    byte[] getSendHeaderEncryptKey2();
    void setDestroyReason(int reason);
    SSU2Bitfield getReceivedMessages();
    SSU2Bitfield getAckedMessages();
    void fragmentsSent(long pktNum, int length, List<PacketBuilder.Fragment> fragments);
    byte getFlags();
}
