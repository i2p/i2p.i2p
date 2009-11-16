package i2p.bote.network;

import i2p.bote.packet.CommunicationPacket;
import i2p.bote.packet.I2PBotePacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionException;
import net.i2p.client.I2PSessionListener;
import net.i2p.client.datagram.I2PDatagramDissector;
import net.i2p.client.datagram.I2PInvalidDatagramException;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * An {@link I2PSessionListener} that receives datagrams from the I2P network and notifies {@link PacketListener}s.
 *
 * @author HungryHobo@mail.i2p
 */
public class I2PPacketDispatcher implements I2PSessionListener {
    private Log log = new Log(I2PPacketDispatcher.class);
    private List<PacketListener> packetListeners;

	public I2PPacketDispatcher() {
		packetListeners = Collections.synchronizedList(new ArrayList<PacketListener>());
	}
    
    public void addPacketListener(PacketListener listener) {
        packetListeners.add(listener);
    }
    
    public void removePacketListener(PacketListener listener) {
        packetListeners.remove(listener);
    }
    
    private void firePacketReceivedEvent(CommunicationPacket packet, Destination sender) {
        for (PacketListener listener: packetListeners)
            listener.packetReceived(packet, sender, System.currentTimeMillis());
    }
            
	public void shutDown() {
	}

	// I2PSessionListener implementation follows
	
    @Override
    public void reportAbuse(I2PSession session, int severity) {
    }
    
    @Override
    public void messageAvailable(I2PSession session, int msgId, long size) {
        byte[] msg = new byte[0];
        try {
            msg = session.receiveMessage(msgId);
        } catch (I2PSessionException e) {
            log.error("Can't get new message from I2PSession.", e);
        }
        I2PDatagramDissector datagramDissector = new I2PDatagramDissector();
        try {
            datagramDissector.loadI2PDatagram(msg);
            datagramDissector.verifySignature();   // TODO keep this line or remove it?
            byte[] payload = datagramDissector.extractPayload();
            Destination sender = datagramDissector.getSender();

            CommunicationPacket packet = CommunicationPacket.createPacket(payload);
            if (packet == null)
                log.debug("Ignoring unparseable packet.");
            else {
                logPacket(packet, sender);
                firePacketReceivedEvent(packet, sender);
            }
        }
        catch (DataFormatException e) {
            log.error("Invalid datagram received.", e);
            e.printStackTrace();
        }
        catch (I2PInvalidDatagramException e) {
            log.error("Datagram failed verification.", e);
            e.printStackTrace();
        }
    }

    private void logPacket(I2PBotePacket packet, Destination sender) {
        String senderHash = sender.calculateHash().toBase64().substring(0, 8) + "...";
        log.debug("I2P packet received: [" + packet + "] Sender: [" + senderHash + "]");
    }
    
    @Override
    public void errorOccurred(I2PSession session, String message, Throwable error) {
        log.error("Router says: " + message, error);
    }
    
    @Override
    public void disconnected(I2PSession session) {
        log.warn("I2P session disconnected.");
    }
}