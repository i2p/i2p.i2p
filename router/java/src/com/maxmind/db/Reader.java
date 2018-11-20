package com.maxmind.db;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instances of this class provide a reader for the MaxMind DB format. IP
 * addresses can be looked up using the <code>get</code> method.
 */
public final class Reader implements Closeable {
    private static final int DATA_SECTION_SEPARATOR_SIZE = 16;
    private static final byte[] METADATA_START_MARKER = {(byte) 0xAB,
            (byte) 0xCD, (byte) 0xEF, 'M', 'a', 'x', 'M', 'i', 'n', 'd', '.',
            'c', 'o', 'm'};

    private final int ipV4Start;
    private final Metadata metadata;
    private final AtomicReference<BufferHolder> bufferHolderReference;
    private final NodeCache cache;

    /**
     * The file mode to use when opening a MaxMind DB.
     */
    public enum FileMode {
        /**
         * The default file mode. This maps the database to virtual memory. This
         * often provides similar performance to loading the database into real
         * memory without the overhead.
         */
        MEMORY_MAPPED,
        /**
         * Loads the database into memory when the reader is constructed.
         */
        MEMORY
    }

    /**
     * Constructs a Reader for the MaxMind DB format, with no caching. The file
     * passed to it must be a valid MaxMind DB file such as a GeoIP2 database
     * file.
     *
     * @param database the MaxMind DB file to use.
     * @throws IOException if there is an error opening or reading from the file.
     */
    public Reader(File database) throws IOException {
        this(database, NoCache.getInstance());
    }

    /**
     * Constructs a Reader for the MaxMind DB format, with the specified backing
     * cache. The file passed to it must be a valid MaxMind DB file such as a
     * GeoIP2 database file.
     *
     * @param database the MaxMind DB file to use.
     * @param cache backing cache instance
     * @throws IOException if there is an error opening or reading from the file.
     */
    public Reader(File database, NodeCache cache) throws IOException {
        this(database, FileMode.MEMORY_MAPPED, cache);
    }

    /**
     * Constructs a Reader with no caching, as if in mode
     * {@link FileMode#MEMORY}, without using a <code>File</code> instance.
     *
     * @param source the InputStream that contains the MaxMind DB file.
     * @throws IOException if there is an error reading from the Stream.
     */
    public Reader(InputStream source) throws IOException {
        this(source, NoCache.getInstance());
    }

    /**
     * Constructs a Reader with the specified backing cache, as if in mode
     * {@link FileMode#MEMORY}, without using a <code>File</code> instance.
     *
     * @param source the InputStream that contains the MaxMind DB file.
     * @param cache backing cache instance
     * @throws IOException if there is an error reading from the Stream.
     */
    public Reader(InputStream source, NodeCache cache) throws IOException {
        this(new BufferHolder(source), "<InputStream>", cache);
    }

    /**
     * Constructs a Reader for the MaxMind DB format, with no caching. The file
     * passed to it must be a valid MaxMind DB file such as a GeoIP2 database
     * file.
     *
     * @param database the MaxMind DB file to use.
     * @param fileMode the mode to open the file with.
     * @throws IOException if there is an error opening or reading from the file.
     */
    public Reader(File database, FileMode fileMode) throws IOException {
        this(database, fileMode, NoCache.getInstance());
    }

    /**
     * Constructs a Reader for the MaxMind DB format, with the specified backing
     * cache. The file passed to it must be a valid MaxMind DB file such as a
     * GeoIP2 database file.
     *
     * @param database the MaxMind DB file to use.
     * @param fileMode the mode to open the file with.
     * @param cache backing cache instance
     * @throws IOException if there is an error opening or reading from the file.
     */
    public Reader(File database, FileMode fileMode, NodeCache cache) throws IOException {
        this(new BufferHolder(database, fileMode), database.getName(), cache);
    }

    private Reader(BufferHolder bufferHolder, String name, NodeCache cache) throws IOException {
        this.bufferHolderReference = new AtomicReference<BufferHolder>(
                bufferHolder);

        if (cache == null) {
            throw new NullPointerException("Cache cannot be null");
        }
        this.cache = cache;

        ByteBuffer buffer = bufferHolder.get();
        int start = this.findMetadataStart(buffer, name);

        Decoder metadataDecoder = new Decoder(this.cache, buffer, start);
        this.metadata = new Metadata((Map) metadataDecoder.decode(start));

        this.ipV4Start = this.findIpV4StartNode(buffer);
    }

    /**
     * Looks up the <code>address</code> in the MaxMind DB.
     *
     * @param ipAddress the IP address to look up.
     * @return the record for the IP address.
     * @throws IOException if a file I/O error occurs.
     */
    public Object get(InetAddress ipAddress) throws IOException {
        ByteBuffer buffer = this.getBufferHolder().get();
        int pointer = this.findAddressInTree(buffer, ipAddress);
        if (pointer == 0) {
            return null;
        }
        return this.resolveDataPointer(buffer, pointer);
    }

    private BufferHolder getBufferHolder() throws ClosedDatabaseException {
        BufferHolder bufferHolder = this.bufferHolderReference.get();
        if (bufferHolder == null) {
            throw new ClosedDatabaseException();
        }
        return bufferHolder;
    }

    private int findAddressInTree(ByteBuffer buffer, InetAddress address)
            throws InvalidDatabaseException {
        byte[] rawAddress = address.getAddress();

        int bitLength = rawAddress.length * 8;
        int record = this.startNode(bitLength);

        for (int i = 0; i < bitLength; i++) {
            if (record >= this.metadata.getNodeCount()) {
                break;
            }
            int b = 0xFF & rawAddress[i / 8];
            int bit = 1 & (b >> 7 - (i % 8));
            record = this.readNode(buffer, record, bit);
        }
        if (record == this.metadata.getNodeCount()) {
            // record is empty
            return 0;
        } else if (record > this.metadata.getNodeCount()) {
            // record is a data pointer
            return record;
        }
        throw new InvalidDatabaseException("Something bad happened");
    }

    private int startNode(int bitLength) {
        // Check if we are looking up an IPv4 address in an IPv6 tree. If this
        // is the case, we can skip over the first 96 nodes.
        if (this.metadata.getIpVersion() == 6 && bitLength == 32) {
            return this.ipV4Start;
        }
        // The first node of the tree is always node 0, at the beginning of the
        // value
        return 0;
    }

    private int findIpV4StartNode(ByteBuffer buffer)
            throws InvalidDatabaseException {
        if (this.metadata.getIpVersion() == 4) {
            return 0;
        }

        int node = 0;
        for (int i = 0; i < 96 && node < this.metadata.getNodeCount(); i++) {
            node = this.readNode(buffer, node, 0);
        }
        return node;
    }

    private int readNode(ByteBuffer buffer, int nodeNumber, int index)
            throws InvalidDatabaseException {
        int baseOffset = nodeNumber * this.metadata.getNodeByteSize();

        switch (this.metadata.getRecordSize()) {
            case 24:
                buffer.position(baseOffset + index * 3);
                return Decoder.decodeInteger(buffer, 0, 3);
            case 28:
                int middle = buffer.get(baseOffset + 3);

                if (index == 0) {
                    middle = (0xF0 & middle) >>> 4;
                } else {
                    middle = 0x0F & middle;
                }
                buffer.position(baseOffset + index * 4);
                return Decoder.decodeInteger(buffer, middle, 3);
            case 32:
                buffer.position(baseOffset + index * 4);
                return Decoder.decodeInteger(buffer, 0, 4);
            default:
                throw new InvalidDatabaseException("Unknown record size: "
                        + this.metadata.getRecordSize());
        }
    }

    private Object resolveDataPointer(ByteBuffer buffer, int pointer)
            throws IOException {
        int resolved = (pointer - this.metadata.getNodeCount())
                + this.metadata.getSearchTreeSize();

        if (resolved >= buffer.capacity()) {
            throw new InvalidDatabaseException(
                    "The MaxMind DB file's search tree is corrupt: "
                            + "contains pointer larger than the database.");
        }

        // We only want the data from the decoder, not the offset where it was
        // found.
        Decoder decoder = new Decoder(this.cache, buffer,
                this.metadata.getSearchTreeSize() + DATA_SECTION_SEPARATOR_SIZE);
        return decoder.decode(resolved);
    }

    /*
     * Apparently searching a file for a sequence is not a solved problem in
     * Java. This searches from the end of the file for metadata start.
     *
     * This is an extremely naive but reasonably readable implementation. There
     * are much faster algorithms (e.g., Boyer-Moore) for this if speed is ever
     * an issue, but I suspect it won't be.
     */
    private int findMetadataStart(ByteBuffer buffer, String databaseName)
            throws InvalidDatabaseException {
        int fileSize = buffer.capacity();

        FILE:
        for (int i = 0; i < fileSize - METADATA_START_MARKER.length + 1; i++) {
            for (int j = 0; j < METADATA_START_MARKER.length; j++) {
                byte b = buffer.get(fileSize - i - j - 1);
                if (b != METADATA_START_MARKER[METADATA_START_MARKER.length - j
                        - 1]) {
                    continue FILE;
                }
            }
            return fileSize - i;
        }
        throw new InvalidDatabaseException(
                "Could not find a MaxMind DB metadata marker in this file ("
                        + databaseName + "). Is this a valid MaxMind DB file?");
    }

    /**
     * @return the metadata for the MaxMind DB file.
     */
    public Metadata getMetadata() {
        return this.metadata;
    }

    /**
     /**
     * <p>
     * Closes the database.
     * </p>
     * <p>
     * If you are using <code>FileMode.MEMORY_MAPPED</code>, this will
     * <em>not</em> unmap the underlying file due to a limitation in Java's
     * <code>MappedByteBuffer</code>. It will however set the reference to
     * the buffer to <code>null</code>, allowing the garbage collector to
     * collect it.
     * </p>
     *
     * @throws IOException if an I/O error occurs.
     */
    @Override
    public void close() throws IOException {
        this.bufferHolderReference.set(null);
    }
}
