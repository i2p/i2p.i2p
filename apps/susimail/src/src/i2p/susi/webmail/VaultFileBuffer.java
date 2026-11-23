package i2p.susi.webmail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import i2p.susi.util.CountingInputStream;
import i2p.susi.util.FileBuffer;

/** Authenticates complete ciphertext before exposing any decrypted bytes. */
final class VaultFileBuffer extends FileBuffer {
    final MailVault vault;
    final boolean gzip;
    private final ThreadLocal<CountingInputStream> readers = new ThreadLocal<CountingInputStream>();
    private volatile long length;
    private OutputStream writer;
    private byte[] staged;
    private long stagedLength;
    private boolean committed, failed;
    private FetchBudget downloadBudget;

    VaultFileBuffer(MailVault vault, java.io.File file, boolean gzip) {
        super(file);
        this.vault = vault;
        this.gzip = gzip;
    }

    VaultFileBuffer downloaded(FetchBudget budget) { downloadBudget = budget; return this; }

    @Override public InputStream getInputStream() throws IOException {
        byte[] plain = vault.read(_file.toPath(), MailVault.MAX_BLOB);
        InputStream source = new FilterInputStream(new ByteArrayInputStream(plain)) {
            @Override public void close() throws IOException { try { super.close(); } finally { Arrays.fill(plain, (byte) 0); } }
        };
        try {
            if (gzip) source = new GZIPInputStream(source);
            CountingInputStream counted = new CountingInputStream(source) {
                private void limit() throws IOException { if (getRead() > 64L * 1024 * 1024) throw new IOException("Decompressed mail exceeds the safety limit"); }
                @Override public int read() throws IOException { int value = super.read(); limit(); return value; }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    int value = super.read(bytes, offset, (int) Math.min(count, Math.max(1, 64L * 1024 * 1024 + 1 - getRead())));
                    limit(); return value;
                }
                @Override public long skip(long count) throws IOException {
                    long skipped = super.skip(Math.min(Math.max(0, count), Math.max(0, 64L * 1024 * 1024 + 1 - getRead())));
                    limit(); return skipped;
                }
                @Override public void close() throws IOException {
                    try { super.close(); } finally { if (readers.get() == this) readers.remove(); }
                }
            };
            readers.set(counted);
            return counted;
        } catch (IOException failure) { source.close(); throw failure; }
    }

    @Override public synchronized OutputStream getOutputStream() throws IOException {
        if (writer != null) return writer;
        class WipingBuffer extends ByteArrayOutputStream {
            WipingBuffer() { super(4096); }
            void erase() { Arrays.fill(buf, (byte) 0); reset(); }
        }
        committed = failed = false;
        WipingBuffer bytes = new WipingBuffer();
        OutputStream bounded = new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= MailVault.MAX_BLOB) throw new IOException("Encrypted mail exceeds the storage limit");
                bytes.write(value);
            }
            @Override public void write(byte[] data, int offset, int count) throws IOException {
                if (count > MailVault.MAX_BLOB - bytes.size()) throw new IOException("Encrypted mail exceeds the storage limit");
                bytes.write(data, offset, count);
            }
        };
        OutputStream encoded = gzip ? new GZIPOutputStream(bounded) : bounded;
        writer = new OutputStream() {
            boolean closed;
            long written;
            @Override public void write(int value) throws IOException { ensure(1); encoded.write(value); written++; }
            @Override public void write(byte[] data, int offset, int count) throws IOException { ensure(count); encoded.write(data, offset, count); written += count; }
            private void ensure(int count) throws IOException {
                if (closed || failed || count < 0 || count > 64L * 1024 * 1024 - written) { failed = true; throw new IOException("Mail write is closed or exceeds its limit"); }
                if (downloadBudget != null) try { downloadBudget.received(count); } catch (IOException limited) { failed = true; throw limited; }
            }
            @Override public void flush() throws IOException { if (!closed) encoded.flush(); }
            @Override public void close() throws IOException {
                if (closed) return;
                closed = true;
                try {
                    encoded.close();
                    if (failed) throw new IOException("Incomplete encrypted mail write");
                    staged = bytes.toByteArray();
                    stagedLength = written;
                } catch (IOException failure) { failed = true; throw failure; }
                finally { bytes.erase(); }
            }
        };
        return writer;
    }

    @Override public void readComplete(boolean success) {
        CountingInputStream reader = readers.get();
        if (reader != null) {
            if (success) length = reader.getRead();
            try { reader.close(); } catch (IOException ignored) {}
            readers.remove();
        }
    }

    @Override public synchronized void writeComplete(boolean success) {
        if (!success) failed = true;
        if (writer != null) try { writer.close(); } catch (IOException failure) { failed = true; }
        if (success && !failed && staged != null) {
            try { vault.write(_file.toPath(), staged); committed = true; length = stagedLength; }
            catch (IOException failure) { failed = true; }
        }
        if (staged != null) { Arrays.fill(staged, (byte) 0); staged = null; }
        writer = null;
        // Only a successful, explicitly completed write replaces the previous ciphertext.
    }

    synchronized boolean isCommitted() { return committed && !failed; }

    @Override public int getLength() { return (int) Math.min(Integer.MAX_VALUE, length); }
}
