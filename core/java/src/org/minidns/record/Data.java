/*
 * Copyright 2015-2020 the original author or authors
 *
 * This software is licensed under the Apache License, Version 2.0,
 * the GNU Lesser General Public License version 2 or later ("LGPL")
 * and the WTFPL.
 * You may choose either license to govern your use of this software only
 * upon the condition that you accept all of the terms of either
 * the Apache License 2.0, the LGPL 2.1+ or the WTFPL.
 */
package org.minidns.record;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import org.minidns.record.Record.TYPE;

/**
 * Generic payload class.
 */
public abstract class Data {

    /**
     * The payload type.
     * @return The payload type.
     */
    public abstract TYPE getType();

    /**
     * The internal method used to serialize Data subclasses.
     *
     * @param dos the output stream to serialize to.
     * @throws IOException if an I/O error occurs.
     */
    protected abstract void serialize(DataOutputStream dos) throws IOException;

    private byte[] bytes;

    private void setBytes() {
        if (bytes != null) return;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        try {
            serialize(dos);
        } catch (IOException e) {
            // Should never happen.
            throw new AssertionError(e);
        }
        bytes = baos.toByteArray();
    }

    public final int length() {
        setBytes();
        return bytes.length;
    }

    public final void toOutputStream(OutputStream outputStream) throws IOException {
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        toOutputStream(dataOutputStream);
    }

    /**
     * Write the binary representation of this payload to the given {@link DataOutputStream}.
     *
     * @param dos the DataOutputStream to write to.
     * @throws IOException if an I/O error occurs.
     */
    public final void toOutputStream(DataOutputStream dos) throws IOException {
        setBytes();
        dos.write(bytes);
    }

    public final byte[] toByteArray() {
        setBytes();
        return bytes.clone();
    }

    private transient Integer hashCodeCache;

    @Override
    public final int hashCode() {
        if (hashCodeCache == null) {
            setBytes();
            hashCodeCache = Arrays.hashCode(bytes);
        }
        return hashCodeCache;
    }

    @Override
    public final boolean equals(Object other) {
        if (!(other instanceof Data)) {
            return false;
        }
        if (other == this) {
            return true;
        }
        Data otherData = (Data) other;
        otherData.setBytes();
        setBytes();

        return Arrays.equals(bytes, otherData.bytes);
    }
}
