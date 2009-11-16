package i2p.bote.packet;

import java.nio.ByteBuffer;

import net.i2p.util.RandomSource;

public class EmailSession {
    private static final byte SESSION_ID_LENGTH = 16;
    
    private byte[] sessionId;
    
    public EmailSession() {
        sessionId = generateSessionId();
    }

    /**
     * Construct a <CODE>EmailSession</CODE> using data read from a <CODE>ByteBuffer</CODE>.
     * @param buffer
     */
    public EmailSession(ByteBuffer buffer) {
        sessionId = new byte[SESSION_ID_LENGTH];
        buffer.get(sessionId);
    }

    public byte[] getSessionId() {
        return sessionId;
    }
    
    private byte[] generateSessionId() {
        RandomSource randomSource = RandomSource.getInstance();
        byte[] sessionId = new byte[SESSION_ID_LENGTH];
        for (int i=0; i<sessionId.length; i++)
            sessionId[i] = (byte)randomSource.nextInt(256);
        return sessionId;
    }
    
    public String toString() {
        StringBuffer buffer = new StringBuffer("[");
        for (int i=0; i<sessionId.length; i++) {
            if (i > 0)
                buffer = buffer.append(" ");
            String hexByte = Integer.toHexString(sessionId[i] & 0xFF);
            if (hexByte.length() < 2)
                buffer = buffer.append("0");
            buffer = buffer.append(hexByte);
        }
        return buffer.append("]").toString();
    }
}