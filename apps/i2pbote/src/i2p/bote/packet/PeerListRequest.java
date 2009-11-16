package i2p.bote.packet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.i2p.util.Log;

@TypeCode('A')
public class PeerListRequest extends CommunicationPacket {
    private Log log = new Log(PeerListRequest.class);

    public PeerListRequest() {
    }

    public PeerListRequest(byte[] data) {
        super(data);
        
        int remaining = data.length - CommunicationPacket.HEADER_LENGTH;
        if (remaining > 0)
            log.debug("Peer List Request packet has " + remaining + " extra bytes.");
    }

    @Override
    public byte[] toByteArray() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        try {
            writeHeader(outputStream);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        
        return outputStream.toByteArray();
    }
}