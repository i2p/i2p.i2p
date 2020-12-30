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

import org.minidns.record.NSEC3.HashAlgorithm;
import org.minidns.record.Record.TYPE;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * NSEC3PARAM record payload.
 */
public class NSEC3PARAM extends Data {

    /**
     * The cryptographic hash algorithm used.
     * 
     */
    public final HashAlgorithm hashAlgorithm;

    /**
     * The cryptographic hash algorithm used.
     * 
     */
    public final byte hashAlgorithmByte;

    public final byte flags;

    /**
     * The number of iterations the hash algorithm is applied.
     */
    public final int /* unsigned short */ iterations;

    /**
     * The salt appended to the next owner name before hashing.
     */
    private final byte[] salt;

    public static NSEC3PARAM parse(DataInputStream dis) throws IOException {
        byte hashAlgorithm = dis.readByte();
        byte flags = dis.readByte();
        int iterations = dis.readUnsignedShort();
        int saltLength = dis.readUnsignedByte();
        byte[] salt = new byte[saltLength];
        if (dis.read(salt) != salt.length && salt.length != 0) throw new IOException();
        return new NSEC3PARAM(hashAlgorithm, flags, iterations, salt);
    }

    private NSEC3PARAM(HashAlgorithm hashAlgorithm, byte hashAlgorithmByte, byte flags, int iterations, byte[] salt) {
        assert hashAlgorithmByte == (hashAlgorithm != null ? hashAlgorithm.value : hashAlgorithmByte);
        this.hashAlgorithmByte = hashAlgorithmByte;
        this.hashAlgorithm = hashAlgorithm != null ? hashAlgorithm : HashAlgorithm.forByte(hashAlgorithmByte);

        this.flags = flags;
        this.iterations = iterations;
        this.salt = salt;
    }

    NSEC3PARAM(byte hashAlgorithm, byte flags, int iterations, byte[] salt) {
        this(null, hashAlgorithm, flags, iterations, salt);
    }

    @Override
    public TYPE getType() {
        return TYPE.NSEC3PARAM;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeByte(hashAlgorithmByte);
        dos.writeByte(flags);
        dos.writeShort(iterations);
        dos.writeByte(salt.length);
        dos.write(salt);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(hashAlgorithm).append(' ')
                .append(flags).append(' ')
                .append(iterations).append(' ')
                .append(salt.length == 0 ? "-" : new BigInteger(1, salt).toString(16).toUpperCase());
        return sb.toString();
    }

    public int getSaltLength() {
        return salt.length;
    }
}
