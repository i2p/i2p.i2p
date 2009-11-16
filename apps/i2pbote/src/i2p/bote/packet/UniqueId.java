package i2p.bote.packet;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;

import net.i2p.data.Base64;
import net.i2p.util.Log;
import net.i2p.util.RandomSource;

public class UniqueId implements Comparable<UniqueId> {
    public static final byte LENGTH = 32;
    
    private Log log = new Log(UniqueId.class);
    private byte[] bytes;

    /**
     * Create a random <code>UniqueId</code>.
     */
    public UniqueId() {
        bytes = new byte[LENGTH];
        for (int i=0; i<LENGTH; i++)
            bytes[i] = (byte)RandomSource.getInstance().nextInt(256);
    }

    /**
     * Create a packet id from a 32 bytes of an array, starting at <code>offset</code>.
     * @param bytes
     */
    public UniqueId(byte[] bytes, int offset) {
        this.bytes = new byte[LENGTH];
        System.arraycopy(bytes, offset, this.bytes, 0, LENGTH);
    }
    
    /**
     * A copy constructor.
     * @param uniqueId
     */
    public UniqueId(UniqueId uniqueId) {
        this.bytes = uniqueId.bytes.clone();
    }
    
    /**
     * Creates a <code>UniqueId</code> using data read from a {@link ByteBuffer}.
     * @param buffer
     */
    public UniqueId(ByteBuffer buffer) {
        bytes = new byte[LENGTH];
        buffer.get(bytes);
    }
    
    public byte[] toByteArray() {
        return bytes;
    }
    
    public String toBase64() {
        return Base64.encode(bytes);
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        outputStream.write(bytes);
    }
    
    @Override
    public int compareTo(UniqueId otherPacketId) {
        return new BigInteger(bytes).compareTo(new BigInteger(otherPacketId.bytes));
    }
    
    @Override
    public String toString() {
        return Base64.encode(bytes);
    }
    
    @Override
    public boolean equals(Object anotherObject) {
        if (!(anotherObject instanceof UniqueId))
            return false;
        UniqueId otherPacketId = (UniqueId)anotherObject;
        
        return Arrays.equals(bytes, otherPacketId.bytes);
    }
    
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}