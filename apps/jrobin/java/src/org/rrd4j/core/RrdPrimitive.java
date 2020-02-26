package org.rrd4j.core;

import java.io.IOException;

abstract class RrdPrimitive<U extends RrdUpdater<U>> {
    static final int STRING_LENGTH = 20;
    static final int RRD_INT = 0, RRD_LONG = 1, RRD_DOUBLE = 2, RRD_STRING = 3;
    static final int[] RRD_PRIM_SIZES = {4, 8, 8, 2 * STRING_LENGTH};

    private RrdBackend backend;
    private int byteCount;
    private final long pointer;
    private final boolean cachingAllowed;

    RrdPrimitive(RrdUpdater<U> updater, int type, boolean isConstant) throws IOException {
        this(updater, type, 1, isConstant);
    }

    RrdPrimitive(RrdUpdater<U> updater, int type, int count, boolean isConstant) throws IOException {
        this.backend = updater.getRrdBackend();
        this.byteCount = RRD_PRIM_SIZES[type] * count;
        this.pointer = updater.getRrdAllocator().allocate(byteCount);
        this.cachingAllowed = isConstant || backend.isCachingAllowed();
    }

    final byte[] readBytes() throws IOException {
        byte[] b = new byte[byteCount];
        backend.read(pointer, b);
        return b;
    }

    final void writeBytes(byte[] b) throws IOException {
        assert b.length == byteCount : "Invalid number of bytes supplied to RrdPrimitive.write method";
        backend.write(pointer, b);
    }

    final int readInt() throws IOException {
        return backend.readInt(pointer);
    }

    final void writeInt(int value) throws IOException {
        backend.writeInt(pointer, value);
    }

    final long readLong() throws IOException {
        return backend.readLong(pointer);
    }

    final void writeLong(long value) throws IOException {
        backend.writeLong(pointer, value);
    }

    final double readDouble() throws IOException {
        return backend.readDouble(pointer);
    }

    final double readDouble(int index) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        return backend.readDouble(offset);
    }

    final double[] readDouble(int index, int count) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        return backend.readDouble(offset, count);
    }

    final void writeDouble(double value) throws IOException {
        backend.writeDouble(pointer, value);
    }

    final void writeDouble(int index,  double value) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        backend.writeDouble(offset, value);
    }

    final void writeDouble(int index, double value, int count) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        backend.writeDouble(offset, value, count);
    }

    final void writeDouble(int index, double[] values) throws IOException {
        long offset = pointer + index * RRD_PRIM_SIZES[RRD_DOUBLE];
        backend.writeDouble(offset, values);
    }

    final String readString() throws IOException {
        return backend.readString(pointer);
    }

    final void writeString(String value) throws IOException {
        backend.writeString(pointer, value);
    }

    protected final <E extends Enum<E>> E readEnum(Class<E> clazz) throws IOException {
        String value = backend.readString(pointer);
        if (value == null || value.isEmpty()) {
            return null;
        } else {
            try {
                return Enum.valueOf(clazz, value);
            } catch (IllegalArgumentException e) {
                throw new InvalidRrdException("Invalid value for " + clazz.getSimpleName(), e);
            }
        }
    }

    protected final <E extends Enum<E>> void writeEnum(E value) throws IOException {
        writeString(value.name());
    }

    final boolean isCachingAllowed() {
        return cachingAllowed;
    }
}
