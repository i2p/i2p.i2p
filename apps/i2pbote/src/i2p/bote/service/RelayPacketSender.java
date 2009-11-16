package i2p.bote.service;

import i2p.bote.Configuration;
import i2p.bote.folder.PacketFolder;
import i2p.bote.network.I2PSendQueue;
import i2p.bote.packet.RelayPacket;

import java.text.ParseException;

import javax.mail.MessagingException;

import com.nettgryppa.security.HashCash;

import net.i2p.I2PAppContext;
import net.i2p.crypto.ElGamalAESEngine;
import net.i2p.crypto.SessionKeyManager;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

/**
 * A background thread that sends packets in the relay outbox to the I2P network.
 *
 * @author HungryHobo@mail.i2p
 */
public class RelayPacketSender extends I2PBoteThread {
    private static final int PAUSE = 10 * 60 * 1000;   // the wait time, in milliseconds,  before processing the folder again
    private static final int PADDED_SIZE = 16 * 1024;
    private static final Log log = new Log(RelayPacketSender.class);
    
    private I2PSendQueue sendQueue;
    private ElGamalAESEngine encrypter = I2PAppContext.getGlobalContext().elGamalAESEngine();
    private SessionKeyManager sessionKeyManager = I2PAppContext.getGlobalContext().sessionKeyManager();
    private PacketFolder<RelayPacket> packetStore;
    private Configuration configuration;
    
    public RelayPacketSender(I2PSendQueue sendQueue, PacketFolder<RelayPacket> packetStore) {
        super("RelayPacketSender");
        this.sendQueue = sendQueue;
        this.packetStore = packetStore;
    }
    
    @Override
    public void run() {
        while (true) {
            if (log.shouldLog(Log.DEBUG))
                log.debug("Deleting expired packets...");
            try {
                deleteExpiredPackets();
            } catch (Exception e) {
                log.error("Error deleting expired packets", e);
            }
            
            log.info("Processing outgoing packets in directory '" + packetStore.getStorageDirectory().getAbsolutePath() + "'");
            for (RelayPacket packet: packetStore) {
                log.info("Processing packet file: <" + packet.getFile() + ">");
                try {
                    HashCash hashCash = null;   // TODO
                    long sendTime = getRandomSendTime(packet);
                    sendQueue.sendRelayRequest(packet, hashCash, sendTime);
                } catch (Exception e) {
                    log.error("Error sending packet. ", e);
                }
            }
            
            try {
                Thread.sleep(PAUSE);
            } catch (InterruptedException e) {
                log.error("RelayPacketSender received an InterruptedException.");
            }
        }
    }
    
    private long getRandomSendTime(RelayPacket packet) {
        long min = packet.getEarliestSendTime();
        long max = packet.getLatestSendTime();
        return min + RandomSource.getInstance().nextLong(max-min);
    }
    
    public void deleteExpiredPackets() throws ParseException, MessagingException {
        // TODO look at filename which = receive time, delete if too old
    }
}