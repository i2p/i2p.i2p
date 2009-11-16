package i2p.bote.packet.dht;

import i2p.bote.packet.CommunicationPacket;
import i2p.bote.packet.RelayPacket;
import i2p.bote.packet.TypeCode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;

import net.i2p.data.Hash;
import net.i2p.util.Log;

import com.nettgryppa.security.HashCash;

@TypeCode('S')
public class StoreRequest extends CommunicationPacket {
    private Log log = new Log(RelayPacket.class);
    private HashCash hashCash;
    private DhtStorablePacket packetToStore;

    public StoreRequest(HashCash hashCash, DhtStorablePacket packetToStore) {
        this.hashCash = hashCash;
        this.packetToStore = packetToStore;
    }
    
    public StoreRequest(byte[] data) throws NoSuchAlgorithmException {
        super(data);
        ByteBuffer buffer = ByteBuffer.wrap(data, HEADER_LENGTH, data.length-HEADER_LENGTH);
        
        int hashCashLength = buffer.getShort();
        byte[] hashCashData = new byte[hashCashLength];
        buffer.get(hashCashData);
        hashCash = new HashCash(new String(hashCashData));
        
        int dataLength = buffer.getShort();
        byte[] storedData = new byte[dataLength];
        buffer.get(storedData);
        packetToStore = DhtStorablePacket.createPacket(storedData);
        
        if (buffer.hasRemaining())
            log.debug("Storage Request Packet has " + buffer.remaining() + " extra bytes.");
    }

    public Hash getKey() {
        return packetToStore.getDhtKey();
    }
    
    public DhtStorablePacket getPacketToStore() {
        return packetToStore;
    }

    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(byteArrayStream);

        try {
            writeHeader(dataStream);
            String hashCashString = hashCash.toString();
            dataStream.writeShort(hashCashString.length());
            dataStream.write(hashCashString.getBytes());
            byte[] dataToStore = packetToStore.toByteArray();
            dataStream.writeShort(dataToStore.length);
            dataStream.write(dataToStore);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        return byteArrayStream.toByteArray();
    }
}