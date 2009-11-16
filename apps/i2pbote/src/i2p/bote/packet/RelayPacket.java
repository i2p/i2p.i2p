package i2p.bote.packet;

import i2p.bote.Util;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.Log;

@TypeCode('R')
public class RelayPacket extends DataPacket {
    public static final int XOR_KEY_LENGTH = 32;   // length of the XOR key in bytes
    
    private Log log = new Log(RelayPacket.class);
    private long earliestSendTime;
    private long latestSendTime;
    private byte[] xorKey;
    private Destination nextDestination;   // an I2P node to send the packet to
    private byte[] payload;   // can contain another Relay Packet, Email Packet, or Retrieve Request

    public RelayPacket(Destination nextDestination, long earliestSendTime, long latestSendTime) {
        this.nextDestination = nextDestination;
        this.earliestSendTime = earliestSendTime;
        this.latestSendTime = latestSendTime;
    }

    public RelayPacket(byte[] data) throws DataFormatException {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        
        earliestSendTime = buffer.getInt();
        latestSendTime = buffer.getInt();
        
        xorKey = new byte[XOR_KEY_LENGTH];
        buffer.get(xorKey);
        
        nextDestination = new Destination();
        byte[] destinationData = new byte[384];
        buffer.get(destinationData);
        nextDestination.readBytes(destinationData, 0);
        
        int payloadLength = buffer.getShort();
        payload = new byte[payloadLength];
        buffer.get(payload);
        
        if (buffer.hasRemaining())
            log.debug("Relay Packet has " + buffer.remaining() + " extra bytes.");
    }
    
    public RelayPacket(DataPacket dataPacket, Destination nextDestination, long earliestSendTime, long latestSendTime, byte[] xorKey) throws DataFormatException {
        // TODO
    }
    
    public RelayPacket(InputStream inputStream) throws IOException, DataFormatException {
        this(Util.readInputStream(inputStream));
    }
    
    public Destination getNextDestination() {
        return nextDestination;
    }

    public long getEarliestSendTime() {
        return earliestSendTime;
    }

    public long getLatestSendTime() {
        return latestSendTime;
    }
    
    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream arrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(arrayOutputStream);
 
        try {
            dataStream.writeInt((int)earliestSendTime);
            dataStream.writeInt((int)latestSendTime);
            dataStream.write(xorKey);
            dataStream.write(nextDestination.toByteArray());
            dataStream.writeShort(payload.length);
            dataStream.write(payload);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        
        return arrayOutputStream.toByteArray();
    }
}