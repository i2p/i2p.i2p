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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.minidns.record.Record.TYPE;

public final class UNKNOWN extends Data {

    private final TYPE type;
    private final byte[] data;

    private UNKNOWN(DataInputStream dis, int payloadLength, TYPE type) throws IOException {
        this.type = type;
        this.data = new byte[payloadLength];
        dis.readFully(data);
    }

    @Override
    public TYPE getType() {
        return type;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.write(data);
    }

    public static UNKNOWN parse(DataInputStream dis, int payloadLength, TYPE type)
            throws IOException {
        return new UNKNOWN(dis, payloadLength, type);
    }

}
