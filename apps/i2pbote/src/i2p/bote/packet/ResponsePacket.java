package i2p.bote.packet;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.i2p.util.Log;

@TypeCode('N')
public class ResponsePacket extends CommunicationPacket {
    private Log log = new Log(EmailPacket.class);
    private StatusCode statusCode;
    private DataPacket payload;

    public ResponsePacket(DataPacket payload, StatusCode statusCode, UniqueId packetId) {
        super(packetId);
        this.payload = payload;
        this.statusCode = statusCode;
    }
    
    public ResponsePacket(byte[] data) {
        super(data);
        ByteBuffer buffer = ByteBuffer.wrap(data, HEADER_LENGTH, data.length-HEADER_LENGTH);

        statusCode = StatusCode.values()[buffer.get()];

        int payloadLength = buffer.getShort();
        if (payloadLength > 0) {
            byte[] payloadData = new byte[payloadLength];
            buffer.get(payloadData);
            payload = DataPacket.createPacket(payloadData);
        }
        
        if (buffer.hasRemaining())
            log.debug("Response Packet has " + buffer.remaining() + " extra bytes.");
    }

    public DataPacket getPayload() {
        return payload;
    }
    
    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(byteStream);
        
        try {
            writeHeader(dataStream);
            dataStream.write(statusCode.ordinal());
            
            byte[] payloadBytes = payload.toByteArray();
            dataStream.writeShort(payloadBytes.length);
            dataStream.write(payloadBytes);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        
        return byteStream.toByteArray();
    }
}