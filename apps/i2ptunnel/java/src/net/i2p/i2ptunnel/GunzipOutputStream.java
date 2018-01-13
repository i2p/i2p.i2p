package net.i2p.i2ptunnel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

import net.i2p.data.DataHelper;

/**
 * Gunzip implementation per 
 * <a href="http://www.faqs.org/rfcs/rfc1952.html">RFC 1952</a>, reusing 
 * java's standard CRC32 and Inflater and InflaterOutputStream implementations.
 *
 * Note that the underlying InflaterOutputStream cannot be reused after close(),
 * so we don't have a Reusable version of this.
 *
 * Modified from net.i2p.util.ResettableGZIPInputStream to use Java 6 InflaterOutputstream
 * @since 0.9.21
 */
class GunzipOutputStream extends InflaterOutputStream {
    private static final int FOOTER_SIZE = 8; // CRC32 + ISIZE
    private final CRC32 _crc32;
    private final byte _buf1[] = new byte[1];
    private boolean _complete;
    private final byte _footer[] = new byte[FOOTER_SIZE];
    private long _bytesReceived;
    private long _bytesReceivedAtCompletion;
    
    private enum HeaderState { MB1, MB2, CF, MT0, MT1, MT2, MT3, EF, OS, FLAGS,
                               EH1, EH2, EHDATA, NAME, COMMENT, CRC1, CRC2, DONE }
    private HeaderState _state = HeaderState.MB1;
    private int _flags;
    private int _extHdrToRead;
    
    /**
     * Build a new Gunzip stream
     */
    public GunzipOutputStream(OutputStream uncompressedStream) throws IOException {
        super(uncompressedStream, new Inflater(true));
        _crc32 = new CRC32();
    }
    
    @Override
    public void write(int b) throws IOException {
        _buf1[0] = (byte) b;
        write(_buf1, 0, 1);
    }
    
    @Override
    public void write(byte buf[]) throws IOException {
        write(buf, 0, buf.length);
    }

    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        if (_complete) {
            // shortcircuit so the inflater doesn't try to refill 
            // with the footer's data (which would fail, causing ZLIB err)
            return;
        }
        boolean isFinished = inf.finished();
        for (int i = off; i < off + len; i++) {
            if (!isFinished) {
                if (_state != HeaderState.DONE) {
                    verifyHeader(buf[i]);
                    continue;
                }
                // ensure we call the same method variant so we don't depend on underlying implementation
                super.write(buf, i, 1);
                if (inf.finished()) {
                    isFinished = true;
                    _bytesReceivedAtCompletion = _bytesReceived;
                }
            }
            _footer[(int) (_bytesReceived++ % FOOTER_SIZE)] = buf[i];
            if (isFinished) {
                long footerSize = _bytesReceivedAtCompletion - _bytesReceived;
                // could be at 7 or 8...
                // we write the first byte of the footer to the Inflater if necessary...
                // see comments in ResettableGZIPInputStream for details
                if (footerSize >= FOOTER_SIZE - 1) {
                    try {
                        verifyFooter();
                        inf.reset(); // so it doesn't bitch about missing data...
                        _complete = true;
                        return;
                    } catch (IOException ioe) {
                        // failed at 7, retry at 8
                        if (footerSize == FOOTER_SIZE - 1 && i < off + len - 1)
                            continue;
                        _complete = true;
                        throw ioe;
                    }
                }
            }
        }
    }
    
    /**
     *  Inflater statistic
     */
    public long getTotalRead() {
        try {
            return inf.getBytesRead(); 
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     *  Inflater statistic
     */
    public long getTotalExpanded() { 
        try {
            return inf.getBytesWritten(); 
        } catch (RuntimeException e) {
            // possible NPE in some implementations
            return 0;
        }
    }

    /**
     *  Inflater statistic
     */
    public long getRemaining() { 
        try {
            return inf.getRemaining(); 
        } catch (RuntimeException e) {
            // possible NPE in some implementations
            return 0;
        }
    }

    /**
     *  Inflater statistic
     */
    public boolean getFinished() { 
        try {
            return inf.finished(); 
        } catch (RuntimeException e) {
            // possible NPE in some implementations
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        _complete = true;
        _state = HeaderState.DONE;
        super.close();
    }

    @Override
    public String toString() { 
        return "GOS read: " + getTotalRead() + " expanded: " + getTotalExpanded() + " remaining: " + getRemaining() + " finished: " + getFinished();
    }

    /**
     *  @throws IOException on CRC or length check fail
     */
    private void verifyFooter() throws IOException {
        int idx = (int) (_bytesReceivedAtCompletion % FOOTER_SIZE);
        byte[] footer;
        if (idx == 0) {
            footer = _footer;
        } else {
            footer = new byte[FOOTER_SIZE];
            for (int i = 0; i < FOOTER_SIZE; i++) {
                footer[i] = _footer[(int) ((_bytesReceivedAtCompletion + i) % FOOTER_SIZE)];
            }
        }
        
        long actualSize = inf.getTotalOut();
        long expectedSize = DataHelper.fromLongLE(footer, 4, 4);
        if (expectedSize != actualSize)
            throw new IOException("gunzip expected " + expectedSize + " bytes, got " + actualSize);
        
        long actualCRC = _crc32.getValue();
        long expectedCRC = DataHelper.fromLongLE(footer, 0, 4);
        if (expectedCRC != actualCRC)
            throw new IOException("gunzip CRC fail expected 0x" + Long.toHexString(expectedCRC) +
                                  " bytes, got 0x" + Long.toHexString(actualCRC));
    }

    /**
     * Make sure the header is valid, throwing an IOException if it is bad.
     * Pushes through the state machine, checking as we go.
     * Call for each byte until HeaderState is DONE.
     */
    private void verifyHeader(byte b) throws IOException {
        int c = b & 0xff;
        switch (_state) {
            case MB1:
                if (c != 0x1F) throw new IOException("First magic byte was wrong [" + c + "]");
                _state = HeaderState.MB2;
                break;

            case MB2:
                if (c != 0x8B) throw new IOException("Second magic byte was wrong [" + c + "]");
                _state = HeaderState.CF;
                break;

            case CF:
                if (c != 0x08) throw new IOException("Compression format is invalid [" + c + "]");
                _state = HeaderState.FLAGS;
                break;

            case FLAGS:
                _flags = c;
                _state = HeaderState.MT0;
                break;

            case MT0:
                // ignore
                _state = HeaderState.MT1;
                break;

            case MT1:
                // ignore
                _state = HeaderState.MT2;
                break;

            case MT2:
                // ignore
                _state = HeaderState.MT3;
                break;

            case MT3:
                // ignore
                _state = HeaderState.EF;
                break;

            case EF:
                if ( (c != 0x00) && (c != 0x02) && (c != 0x04) ) 
	            throw new IOException("Invalid extended flags [" + c + "]");
                _state = HeaderState.OS;
                break;

            case OS:
                // ignore
                if (0 != (_flags & (1<<5)))
                    _state = HeaderState.EH1;
                else if (0 != (_flags & (1<<4)))
                    _state = HeaderState.NAME;
                else if (0 != (_flags & (1<<3)))
                    _state = HeaderState.COMMENT;
                else if (0 != (_flags & (1<<6)))
                    _state = HeaderState.CRC1;
                else
                    _state = HeaderState.DONE;
                break;

            case EH1:
                _extHdrToRead = c;
                _state = HeaderState.EH2;
                break;

            case EH2:
                _extHdrToRead += (c << 8);
                if (_extHdrToRead > 0)
                   _state = HeaderState.EHDATA;
                else if (0 != (_flags & (1<<4)))
                    _state = HeaderState.NAME;
                if (0 != (_flags & (1<<3)))
                    _state = HeaderState.COMMENT;
                else if (0 != (_flags & (1<<6)))
                    _state = HeaderState.CRC1;
                else
                    _state = HeaderState.DONE;
                break;

            case EHDATA:
                // ignore
                if (--_extHdrToRead <= 0) {
                    if (0 != (_flags & (1<<4)))
                        _state = HeaderState.NAME;
                    if (0 != (_flags & (1<<3)))
                        _state = HeaderState.COMMENT;
                    else if (0 != (_flags & (1<<6)))
                        _state = HeaderState.CRC1;
                    else
                        _state = HeaderState.DONE;
                }
                break;

            case NAME:
                // ignore
                if (c == 0) {
                    if (0 != (_flags & (1<<3)))
                        _state = HeaderState.COMMENT;
                    else if (0 != (_flags & (1<<6)))
                        _state = HeaderState.CRC1;
                    else
                        _state = HeaderState.DONE;
                }
                break;

            case COMMENT:
                // ignore
                if (c == 0) {
                    if (0 != (_flags & (1<<6)))
                        _state = HeaderState.CRC1;
                    else
                        _state = HeaderState.DONE;
                }
                break;

            case CRC1:
                // ignore
                _state = HeaderState.CRC2;
                break;

            case CRC2:
                // ignore
                _state = HeaderState.DONE;
                break;

            case DONE:
            default:
                break;
        }
    }

/****
    public static void main(String args[]) {
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 1050; i++) {
            byte[] b = new byte[i];
            r.nextBytes(b);
            if (!test(b)) return;
        }
        for (int i = 1; i < 64*1024; i+= 29) {
            byte[] b = new byte[i];
            r.nextBytes(b);
            if (!test(b)) return;
        }
    }
    
    private static boolean test(byte[] b) {
        int size = b.length;
        try { 
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(size);
            java.util.zip.GZIPOutputStream o = new java.util.zip.GZIPOutputStream(baos);
            o.write(b);
            o.finish();
            o.flush();
            byte compressed[] = baos.toByteArray();
            
            java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream(size);
            GunzipOutputStream out = new GunzipOutputStream(baos2);
            out.write(compressed);
            byte rv[] = baos2.toByteArray();
            if (rv.length != b.length)
                throw new RuntimeException("read length: " + rv.length + " expected: " + b.length);
            
            if (!net.i2p.data.DataHelper.eq(rv, 0, b, 0, b.length)) {
                throw new RuntimeException("foo, read=" + rv.length);
            } else {
                System.out.println("match, w00t @ " + size);
                return true;
            }
        } catch (Exception e) { 
            System.out.println("Error dealing with size=" + size + ": " + e.getMessage());
            e.printStackTrace(); 
            return false;
        }
    }
****/
}
