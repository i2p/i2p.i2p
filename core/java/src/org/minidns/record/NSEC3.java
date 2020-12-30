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

import org.minidns.dnslabel.DnsLabel;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base32;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NSEC3 record payload.
 */
public class NSEC3 extends Data {

    /**
     * This Flag indicates whether this NSEC3 RR may cover unsigned
     * delegations.
     */
    public static final byte FLAG_OPT_OUT = 0x1;

    private static final Map<Byte, HashAlgorithm> HASH_ALGORITHM_LUT = new HashMap<>();

    /**
     * DNSSEC NSEC3 Hash Algorithms.
     *
     * @see <a href=
     *      "https://www.iana.org/assignments/dnssec-nsec3-parameters/dnssec-nsec3-parameters.xhtml#dnssec-nsec3-parameters-3">
     *      IANA DNSSEC NSEC3 Hash Algorithms</a>
     */
    public enum HashAlgorithm {
        RESERVED(0, "Reserved"),
        SHA1(1, "SHA-1"),
        ;

        HashAlgorithm(int value, String description) {
            if (value < 0 || value > 255) {
                throw new IllegalArgumentException();
            }
            this.value = (byte) value;
            this.description = description;
            HASH_ALGORITHM_LUT.put(this.value, this);
        }

        public final byte value;
        public final String description;

        public static HashAlgorithm forByte(byte b) {
            return HASH_ALGORITHM_LUT.get(b);
        }
    }

    /**
     * The cryptographic hash algorithm used. If MiniDNS
     * isn't aware of the hash algorithm, then this field will be
     * <code>null</code>.
     * 
     * @see #hashAlgorithmByte
     */
    public final HashAlgorithm hashAlgorithm;

    /**
     * The byte value of the cryptographic hash algorithm used.
     */
    public final byte hashAlgorithmByte;

    /**
     * Bitmap of flags: {@link #FLAG_OPT_OUT}.
     */
    public final byte flags;

    /**
     * The number of iterations the hash algorithm is applied.
     */
    public final int /* unsigned short */ iterations;

    /**
     * The salt appended to the next owner name before hashing.
     */
    private final byte[] salt;

    /**
     * The next hashed owner name in hash order.
     */
    private final byte[] nextHashed;

    private final byte[] typeBitmap;

    /**
     * The RR types existing at the original owner name.
     */
    public final List<TYPE> types;

    public static NSEC3 parse(DataInputStream dis, int length) throws IOException {
        byte hashAlgorithm = dis.readByte();
        byte flags = dis.readByte();
        int iterations = dis.readUnsignedShort();
        int saltLength = dis.readUnsignedByte();
        byte[] salt = new byte[saltLength];
        if (dis.read(salt) != salt.length) throw new IOException();
        int hashLength = dis.readUnsignedByte();
        byte[] nextHashed = new byte[hashLength];
        if (dis.read(nextHashed) != nextHashed.length) throw new IOException();
        byte[] typeBitmap = new byte[length - (6 + saltLength + hashLength)];
        if (dis.read(typeBitmap) != typeBitmap.length) throw new IOException();
        List<TYPE> types = NSEC.readTypeBitMap(typeBitmap);
        return new NSEC3(hashAlgorithm, flags, iterations, salt, nextHashed, types);
    }

    private NSEC3(HashAlgorithm hashAlgorithm, byte hashAlgorithmByte, byte flags, int iterations, byte[] salt, byte[] nextHashed, List<TYPE> types) {
        assert hashAlgorithmByte == (hashAlgorithm != null ? hashAlgorithm.value : hashAlgorithmByte);
        this.hashAlgorithmByte = hashAlgorithmByte;
        this.hashAlgorithm = hashAlgorithm != null ? hashAlgorithm : HashAlgorithm.forByte(hashAlgorithmByte);

        this.flags = flags;
        this.iterations = iterations;
        this.salt = salt;
        this.nextHashed = nextHashed;
        this.types = types;
        this.typeBitmap = NSEC.createTypeBitMap(types);
    }

    public NSEC3(byte hashAlgorithm, byte flags, int iterations, byte[] salt, byte[] nextHashed, List<TYPE> types) {
        this(null, hashAlgorithm, flags, iterations, salt, nextHashed, types);
    }

    public NSEC3(byte hashAlgorithm, byte flags, int iterations, byte[] salt, byte[] nextHashed, TYPE... types) {
        this(null, hashAlgorithm, flags, iterations, salt, nextHashed, Arrays.asList(types));
    }

    @Override
    public TYPE getType() {
        return TYPE.NSEC3;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeByte(hashAlgorithmByte);
        dos.writeByte(flags);
        dos.writeShort(iterations);
        dos.writeByte(salt.length);
        dos.write(salt);
        dos.writeByte(nextHashed.length);
        dos.write(nextHashed);
        dos.write(typeBitmap);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(hashAlgorithm).append(' ')
                .append(flags).append(' ')
                .append(iterations).append(' ')
                .append(salt.length == 0 ? "-" : new BigInteger(1, salt).toString(16).toUpperCase()).append(' ')
                .append(Base32.encodeToString(nextHashed));
        for (TYPE type : types) {
            sb.append(' ').append(type);
        }
        return sb.toString();
    }

    public byte[] getSalt() {
        return salt.clone();
    }

    public int getSaltLength() {
        return salt.length;
    }

    public byte[] getNextHashed() {
        return nextHashed.clone();
    }

    private String nextHashedBase32Cache;

    public String getNextHashedBase32() {
        if (nextHashedBase32Cache == null) {
            nextHashedBase32Cache = Base32.encodeToString(nextHashed);
        }
        return nextHashedBase32Cache;
    }

    private DnsLabel nextHashedDnsLabelCache;

    public DnsLabel getNextHashedDnsLabel() {
        if (nextHashedDnsLabelCache == null) {
            String nextHashedBase32 = getNextHashedBase32();
            nextHashedDnsLabelCache = DnsLabel.from(nextHashedBase32);
        }
        return nextHashedDnsLabelCache;
    }

    public void copySaltInto(byte[] dest, int destPos) {
        System.arraycopy(salt, 0, dest, destPos, salt.length);
    }
}
