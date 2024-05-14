package net.i2p.i2ptunnel.util;

import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import net.i2p.data.DataHelper;

/**
 * Simple stream for checking and optionally removing RFC2616 chunked encoding to the output.
 *
 * @since 0.9.62
 */
public class DechunkedOutputStream extends LimitOutputStream {
    private final boolean _strip;
    private State _state = State.LEN;
    // During main part, how much is remaining in the chunk
    // During the trailer, counts the trailer header size
    private int _remaining;

    private static final byte[] CRLF = DataHelper.getASCII("\r\n");
    
    private enum State { LEN, CR, LF, DATA, DATACR, DATALF, TRAILER, DONE }

    public DechunkedOutputStream(OutputStream raw, DoneCallback callback, boolean strip) {
        super(raw, callback);
        _strip = strip;
    }

    @Override
    public void write(byte buf[], int off, int len) throws IOException {
        if (len <= 0)
            return;

        for (int i = 0; i < len; i++) {
            // _state is what we are expecting next
            //System.err.println("State: " + _state + " i=" + i + " len=" + len + " remaining=" + _remaining + " char=0x" + Integer.toHexString(buf[off + i] & 0xff));
            switch (_state) {
                // collect chunk len and possible ';' then wait for extension if any and CRLF
                case LEN: {
                    int c = buf[off + i] & 0xff;
                    if (c >= '0' && c <= '9') {
                        if (_remaining >= 0x8000000)
                            throw new IOException("Chunk length too big");
                        _remaining <<= 4;
                        _remaining |= c - '0';
                    } else if (c >= 'a' && c <= 'f') {
                        if (_remaining >= 0x800000)
                            throw new IOException("Chunk length too big");
                        _remaining <<= 4;
                        _remaining |= 10 + c - 'a';
                    } else if (c >= 'A' && c <= 'F') {
                        if (_remaining >= 0x800000)
                            throw new IOException("Chunk length too big");
                        _remaining <<= 4;
                        _remaining |= 10 + c - 'A';
                    } else if (c == ';') {
                        _state = State.CR;
                    } else if (c == '\r') {
                        _state = State.LF;
                    } else if (c == '\n') {
                        if (_remaining > 0)
                            _state = State.DATA;
                        else
                            _state = State.TRAILER;
                    } else {
                        throw new IOException("Unexpected length char 0x" + Integer.toHexString(c));
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }
 
                // collect any chunk extension and CR then wait for LF
                case CR: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\r') {
                        _state = State.LF;
                    } else if (c == '\n') {
                        if (_remaining > 0)
                            _state = State.DATA;
                        else
                            _state = State.TRAILER;
                    } else {
                        // chunk extension between the ';' and the CR
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }
 
                // collect LF then wait for DATA
                case LF: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\n') {
                        if (_remaining > 0)
                            _state = State.DATA;
                        else
                            _state = State.TRAILER;
                    } else {
                        throw new IOException("no LF after CR");
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }
 
                // collect DATA then wait for LEN
                case DATA: {
                    int towrite = Math.min(_remaining, len - i);
                    out.write(buf, off + i, towrite);
                    // loop will increment
                    i += towrite - 1;
                    _remaining -= towrite;
                    if (_remaining <= 0)
                        _state = State.DATACR;
                    break;
                }

                // after DATA, collect CR then wait for LF
                case DATACR: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\r')
                        _state = State.DATALF;
                    else if (c == '\n')
                        _state = State.LEN;
                    // else no CRLF?
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }
 
                // after DATA, collect LF then wait for LEN
                case DATALF: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\n')
                        _state = State.LEN;
                    // else no CRLF?
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }
 
 
                // swallow and discard the Trailer headers until we find a plain CRLF
                // we reuse _remaining here to count the size of the header
                case TRAILER: {
                    int c = buf[off + i] & 0xff;
                    if (c == '\r') {
                        // stay here
                    } else if (c == '\n') {
                        if (_remaining <= 0) {
                            // that's it!
                            if (!_strip)
                                out.write(buf, off + i, 1);
                            _state = State.DONE;
                            setDone();
                            return;
                        } else {
                            // stay here
                            _remaining = 0;
                        }
                    } else {
                        _remaining++;
                    }
                    if (!_strip)
                        out.write(buf, off + i, 1);
                    break;
                }

                case DONE: {
                    throw new EOFException((len - i) + " extra bytes written after chunking done");
                }
            }
        }
    }

/*
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: DechunkedOutputStream true/false < in > out");
            System.exit(1);
        }
        Test test = new Test();
        boolean strip = Boolean.parseBoolean(args[0]);
        test.test(strip);
    }

    static class Test implements DoneCallback {
        private boolean run = true;

        public void test(boolean strip) throws Exception {
            LimitOutputStream cout = new DechunkedOutputStream(System.out, this, strip);
            final byte buf[] = new byte[4096];
            try {
                int read;
                while (run && (read = System.in.read(buf)) != -1) {
                    cout.write(buf, 0, read);
                }   
            } finally {   
                cout.close();
            }   
        }   

        public void streamDone() {
            System.err.println("Done");
            run = false;
        }
    }
*/

}
