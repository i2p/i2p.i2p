package net.i2p.client.streaming;

/** 
 * Like a StringBuffer, but for bytes.  This class is not internally synchronized,
 * so care should be taken when using in a multithreaded environment.
 *
 * @deprecated Only used by deprecated I2PSocketImpl
 */
class ByteCollector {
    byte[] contents;
    int size;

    private static final int INITIAL_CAPACITY = 1024;
    private static final int SHORT_CAPACITY = 80;
    
    /**
     * New collector with the default initial capacity
     *
     */
    public ByteCollector() {
        this(INITIAL_CAPACITY);
    }
    
    /**
     * New collector with an initial capacity as specified
     *
     */
    public ByteCollector(int capacity) {
        contents = new byte[capacity];
        size = 0;
    }

    
    /**
     * New collector containing the specified bytes
     *
     */
    public ByteCollector(byte[] b) {
        this();
        append(b);
    }

    /**
     * New collector with the specified byte
     *
     */
    public ByteCollector(byte b) {
        this();
        append(b);
    }

    /**
     * Add a new byte to the collector (extending the buffer if necessary) 
     *
     * @param b byte to add
     * @return this object
     */
    public ByteCollector append(byte b) {
        ensureCapacity(size + 1);
        contents[size++] = b;
        return this;
    }

    /**
     * Add new bytes to the collector (extending the buffer if necessary) 
     *
     * @param b bytes to add
     * @return this object
     */
    public ByteCollector append(byte[] b) {
        ensureCapacity(size + b.length);
        System.arraycopy(b, 0, contents, size, b.length);
        size += b.length;
        return this;
    }

    /**
     * Add new bytes to the collector (extending the buffer if necessary) 
     *
     * @param b byte array to add from
     * @param len number of bytes in the array to add
     * @return this object
     */
    public ByteCollector append(byte[] b, int len) {
        return append(b, 0, len);
    }

    /**
     * Add new bytes to the collector (extending the buffer if necessary) 
     *
     * @param b byte array to add from
     * @param off offset into the array to begin adding from
     * @param len number of bytes in the array to add
     * @return this object
     */
    public ByteCollector append(byte[] b, int off, int len) {
        ensureCapacity(size + len);
        System.arraycopy(b, off, contents, size, len);
        size += len;
        return this;
    }

    /**
     * Add the contents of the byte collector to the current collector (extending the buffer if necessary) 
     *
     * @param bc collector to copy 
     * @return this object
     */
    public ByteCollector append(ByteCollector bc) {
        // optimieren?
        return append(bc.toByteArray());
    }

    /**
     * Copy the contents of the collector into a new array and return it
     *
     * @return new array containing a copy of the current collector's data
     */
    public byte[] toByteArray() {
        byte[] result = new byte[size];
        System.arraycopy(contents, 0, result, 0, size);
        return result;
    }

    /**
     * Pull off the first $maxlen bytes from the collector, shifting the remaining
     * bytes into the beginning of the collector's array.
     *
     * @param maxlen max number of bytes we want to pull from the collector (we will get
     *               less if the collector doesnt have that many bytes yet)
     * @return copy of the bytes pulled from the collector
     */
    public byte[] startToByteArray(int maxlen) {
        if (size < maxlen) {
            byte[] res = toByteArray();
            clear();
            return res;
        } else {
            byte[] result = new byte[maxlen];
            System.arraycopy(contents, 0, result, 0, maxlen);
            System.arraycopy(contents, maxlen, contents, 0, size - maxlen);
            size -= maxlen;
            return result;
        }
    }

    /**
     * How many bytes are available for retrieval?
     *
     * @return number of bytes
     */
    public int getCurrentSize() {
        return size;
    }

    /**
     * Make sure we have sufficient storage space.
     *
     * @param cap minimum number of bytes that the buffer should contain
     * @return true if the the collector was expanded to meet the minimum, 
     *         false if it was already large enough
     */
    public boolean ensureCapacity(int cap) {
        if (contents.length < cap) {
            int l = contents.length;
            while (l < cap) {
                l = (l * 3) / 2 + 1;
            }
            byte[] newcont = new byte[l];
            System.arraycopy(contents, 0, newcont, 0, size);
            contents = newcont;
            return true;
        }
        return false;
    }

    /**
     * Does the collector have meaningful data or is it empty?
     *
     * @return true if it has no data
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Search through the collector for the first occurrence of the sequence of 
     * bytes contained within the specified collector
     *
     * @param bc bytes that will be searched for
     * @return index into the current collector, or -1 if it isn't found
     */
    public int indexOf(ByteCollector bc) {
        // optimieren?
        return indexOf(bc.toByteArray());
    }

    /**
     * Search through the collector for the first occurrence of the specified 
     * byte
     *
     * @param b byte that will be searched for
     * @return index into the current collector, or -1 if it isn't found
     */
    public int indexOf(byte b) {
        // optimieren?
        return indexOf(new byte[] { b});
    }

    /**
     * Search through the collector for the first occurrence of the sequence of 
     * bytes
     *
     * @param ba bytes that will be searched for
     * @return index into the current collector, or -1 if it isn't found
     */
    public int indexOf(byte[] ba) {
        loop: for (int i = 0; i < size - ba.length + 1; i++) {
            for (int j = 0; j < ba.length; j++) {
                if (contents[i + j] != ba[j]) continue loop;
            }
            return i;
        }
        return -1;
    }

    /**
     * Empty the collector.  This does not affect its capacity.
     *
     */
    public void clear() {
        size = 0;
    }

    /**
     * Empty the collector and reduce its capacity.
     *
     */
    public void clearAndShorten() {
        size = 0;
        contents = new byte[SHORT_CAPACITY];
    }

    /**
     * Render the bytes as a string
     *
     * @return the, uh, string
     */
    @Override
    public String toString() {
        return new String(toByteArray());
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < size; i++) {
            h += contents[i] * contents[i];
        }
        return h;
    }

    /**
     * Compare the collectors.  
     * 
     * @return true if and only if both are the same size and the 
     *         byte arrays they contain are equal.
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof ByteCollector) {
            ByteCollector by = (ByteCollector) o;
            if (size != by.size) return false;
            for (int i = 0; i < size; i++) {
                if (contents[i] != by.contents[i]) return false;
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Remove the first byte from the collector, shifting its contents accordingly.
     *
     * @return byte removed
     * @throws IllegalArgumentException if the collector is empty
     */
    public byte removeFirst() throws IllegalArgumentException {
        byte bb = contents[0];
        if (size == 0) throw new IllegalArgumentException("ByteCollector is empty");
        if (size > 1)
            System.arraycopy(contents, 1, contents, 0, --size);
        else
            size = 0;
        return bb;
    }
}
