package i2p.bote.packet.dht;

import i2p.bote.I2PBote;
import i2p.bote.packet.CommunicationPacket;
import i2p.bote.packet.I2PBotePacket;
import i2p.bote.packet.TypeCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.i2p.data.Hash;
import net.i2p.util.Log;

@TypeCode('Q')
public class RetrieveRequest extends CommunicationPacket {
    private Log log = new Log(I2PBote.class);
    private Hash key;
    private Class<? extends DhtStorablePacket> dataType;

    public RetrieveRequest(Hash key, Class<? extends DhtStorablePacket> dataType) {
        this.key = key;
        this.dataType = dataType;
    }
    
    public RetrieveRequest(byte[] data) {
        super(data);
        ByteBuffer buffer = ByteBuffer.wrap(data, HEADER_LENGTH, data.length-HEADER_LENGTH);
        
        char dataTypeCode = (char)buffer.get();
        dataType = DhtStorablePacket.decodePacketTypeCode(dataTypeCode);
        
        byte[] keyBytes = new byte[Hash.HASH_LENGTH];
        buffer.get(keyBytes);
        key = new Hash(keyBytes);
        
        if (buffer.hasRemaining())
            log.debug("Retrieve Request Packet has " + buffer.remaining() + " extra bytes.");
    }

    public Hash getKey() {
        return key;
    }
    
    public Class<? extends I2PBotePacket> getDataType() {
        return dataType;
    }

    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        try {
            writeHeader(outputStream);
            outputStream.write(getPacketTypeCode(dataType));
            outputStream.write(key.toByteArray());
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        
        return outputStream.toByteArray();
    }
}