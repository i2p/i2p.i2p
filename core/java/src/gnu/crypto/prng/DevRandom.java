package gnu.crypto.prng;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Map;

import net.i2p.data.DataHelper;

/**
 * Simple /dev/random reader
 *
 * @since 0.9.58
 */
class DevRandom implements IRandomStandalone {

    private static final String F = "/dev/random";
    private final File file = new File(F);

    /**
     *  @since 0.9.66
     */
    public static boolean isSupported() {
        return (new File(F)).canRead();
    }

    public String name() { return F; }

    public void init(Map<String, byte[]> attributes) {
        if (!isSupported())
            throw new IllegalStateException("Cannot open " + F);
    }

    public byte nextByte() {
        throw new IllegalStateException("unsupported");
    }

    public void nextBytes(byte[] out) throws IllegalStateException {
        nextBytes(out, 0, out.length);
    }

    public void nextBytes(byte[] out, int offset, int length) throws IllegalStateException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            DataHelper.read(in, out, offset, length);
        } catch (IOException ioe) {
            throw new IllegalStateException("Read failed " + F, ioe);
        } finally {
             if (in != null) try { in.close(); } catch (IOException ioe2) {}
        }
    }

    public void addRandomByte(byte b) {}
    public void addRandomBytes(byte[] in) {}
    public void addRandomBytes(byte[] in, int offset, int length) {}

    @Override
    public Object clone() throws CloneNotSupportedException { return super.clone(); }
}
