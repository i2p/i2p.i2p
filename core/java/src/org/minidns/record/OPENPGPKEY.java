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

import org.minidns.util.Base64;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class OPENPGPKEY extends Data {

    private final byte[] publicKeyPacket;

    public static OPENPGPKEY parse(DataInputStream dis, int length) throws IOException {
        byte[] publicKeyPacket = new byte[length];
        dis.readFully(publicKeyPacket);
        return new OPENPGPKEY(publicKeyPacket);
    }

    OPENPGPKEY(byte[] publicKeyPacket) {
        this.publicKeyPacket = publicKeyPacket;
    }

    @Override
    public Record.TYPE getType() {
        return Record.TYPE.OPENPGPKEY;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.write(publicKeyPacket);
    }

    @Override
    public String toString() {
        return getPublicKeyPacketBase64();
    }

    private transient String publicKeyPacketBase64Cache;

    public String getPublicKeyPacketBase64() {
        if (publicKeyPacketBase64Cache == null) {
            publicKeyPacketBase64Cache = Base64.encodeToString(publicKeyPacket);
        }
        return publicKeyPacketBase64Cache;
    }

    public byte[] getPublicKeyPacket() {
        return publicKeyPacket.clone();
    }
}
