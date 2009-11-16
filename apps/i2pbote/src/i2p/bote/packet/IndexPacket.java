package i2p.bote.packet;

import i2p.bote.EmailDestination;
import i2p.bote.packet.dht.DhtStorablePacket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * This class is not thread-safe.
 *
 * @author HungryHobo@mail.i2p
 */
@TypeCode('I')
public class IndexPacket extends DhtStorablePacket {
    private Log log = new Log(IndexPacket.class);
    private Collection<Hash> dhtKeys;   // DHT keys of email packets
    private Hash destinationHash;   // The DHT key of this packet
    
    public IndexPacket(byte[] data) {
        ByteBuffer dataBuffer = ByteBuffer.wrap(data);
        if (dataBuffer.get() != getPacketTypeCode())
            log.error("Wrong type code for IndexPacket. Expected <" + getPacketTypeCode() + ">, got <" + (char)data[0] + ">");
        
        destinationHash = readHash(dataBuffer);

        int numKeys = dataBuffer.get();
        
        dhtKeys = new ArrayList<Hash>();
        for (int i=0; i<numKeys; i++) {
            Hash dhtKey = readHash(dataBuffer);
            dhtKeys.add(dhtKey);
        }
        
        // TODO catch BufferUnderflowException; warn if extra bytes in the array
    }

    public IndexPacket(Collection<EmailPacket> emailPackets, EmailDestination emailDestination) {
        dhtKeys = new ArrayList<Hash>();
        for (EmailPacket emailPacket: emailPackets)
            dhtKeys.add(emailPacket.getDhtKey());
        
        destinationHash = emailDestination.getHash();
    }
    
    /**
     * Merges the DHT keys of multiple index packets into one big index packet.
     * The DHT key of this packet is not initialized.
     * @param indexPackets
     */
    public IndexPacket(Collection<IndexPacket> indexPackets) {
        dhtKeys = new HashSet<Hash>();
        for (IndexPacket packet: indexPackets)
            dhtKeys.addAll(packet.getDhtKeys());
    }
    
    /**
     * A varargs version of {@link IndexPacket(Collection<IndexPacket>)}.
     * @param indexPackets
     */
    public IndexPacket(IndexPacket... indexPackets) {
        this(Arrays.asList(indexPackets));
    }
    
    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(getPacketTypeCode());
        try {
            outputStream.write((byte)getPacketTypeCode());
            destinationHash.writeBytes(outputStream);
            outputStream.write((byte)dhtKeys.size());
            for (Hash dhtKey: dhtKeys)
                dhtKey.writeBytes(outputStream);
            // TODO in the unit test, verify that toByteArray().length = Hash.NUM_BYTES + 1 + dhtKeys.size()*Hash.NUM_BYTES
        } catch (DataFormatException e) {
            log.error("Invalid format for email destination.", e);
        } catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        return outputStream.toByteArray();
    }

    /**
     * Returns the DHT keys of the {@link EmailPacket}s referenced by this {@link IndexPacket}.
     * @return
     */
    public Collection<Hash> getDhtKeys() {
        return dhtKeys;
    }
    
    /**
     * Returns the DHT key of this packet.
     */
    @Override
    public Hash getDhtKey() {
        return destinationHash;
    }
}