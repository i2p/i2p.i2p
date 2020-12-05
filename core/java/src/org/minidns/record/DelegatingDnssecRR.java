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
import java.math.BigInteger;
import java.util.Arrays;

import org.minidns.constants.DnssecConstants.DigestAlgorithm;
import org.minidns.constants.DnssecConstants.SignatureAlgorithm;

/**
 * DS (Delegation Signer) record payload.
 *
 * @see <a href="https://tools.ietf.org/html/rfc4034#section-5">RFC 4034 § 5</a>
 */
public abstract class DelegatingDnssecRR extends Data {

    /**
     * The key tag value of the DNSKEY RR that validates this signature.
     */
    public final int /* unsigned short */ keyTag;

    /**
     * The cryptographic algorithm used to create the signature. If MiniDNS
     * isn't aware of the signature algorithm, then this field will be
     * <code>null</code>.
     * 
     * @see #algorithmByte
     */
    public final SignatureAlgorithm algorithm;

    /**
     * The byte value of the cryptographic algorithm used to create the signature.
     */
    public final byte algorithmByte;

    /**
     * The algorithm used to construct the digest. If MiniDNS
     * isn't aware of the digest algorithm, then this field will be
     * <code>null</code>.
     * 
     * @see #digestTypeByte
     */
    public final DigestAlgorithm digestType;

    /**
     * The byte value of algorithm used to construct the digest.
     */
    public final byte digestTypeByte;

    /**
     * The digest build from a DNSKEY.
     */
    protected final byte[] digest;

    protected static SharedData parseSharedData(DataInputStream dis, int length) throws IOException {
        int keyTag = dis.readUnsignedShort();
        byte algorithm = dis.readByte();
        byte digestType = dis.readByte();
        byte[] digest = new byte[length - 4];
        if (dis.read(digest) != digest.length) throw new IOException();
        return new SharedData(keyTag, algorithm, digestType, digest);
    }

    protected static final class SharedData {
        protected final int keyTag;
        protected final byte algorithm;
        protected final byte digestType;
        protected final byte[] digest;

        private SharedData(int keyTag, byte algorithm, byte digestType, byte[] digest) {
            this.keyTag = keyTag;
            this.algorithm = algorithm;
            this.digestType = digestType;
            this.digest = digest;
        }
    }

    protected DelegatingDnssecRR(int keyTag, SignatureAlgorithm algorithm, byte algorithmByte, DigestAlgorithm digestType, byte digestTypeByte, byte[] digest) {
        this.keyTag = keyTag;

        assert algorithmByte == (algorithm != null ? algorithm.number : algorithmByte);
        this.algorithmByte = algorithmByte;
        this.algorithm = algorithm != null ? algorithm : SignatureAlgorithm.forByte(algorithmByte);

        assert digestTypeByte == (digestType != null ? digestType.value : digestTypeByte);
        this.digestTypeByte = digestTypeByte;
        this.digestType = digestType != null ? digestType : DigestAlgorithm.forByte(digestTypeByte);

        assert digest != null;
        this.digest = digest;
    }

    protected DelegatingDnssecRR(int keyTag, byte algorithm, byte digestType, byte[] digest) {
        this(keyTag, null, algorithm, null, digestType, digest);
    }

    protected DelegatingDnssecRR(int keyTag, SignatureAlgorithm algorithm, DigestAlgorithm digestType, byte[] digest) {
        this(keyTag, algorithm, algorithm.number, digestType, digestType.value, digest);
    }

    protected DelegatingDnssecRR(int keyTag, SignatureAlgorithm algorithm, byte digestType, byte[] digest) {
        this(keyTag, algorithm, algorithm.number, null, digestType, digest);
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeShort(keyTag);
        dos.writeByte(algorithmByte);
        dos.writeByte(digestTypeByte);
        dos.write(digest);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(keyTag).append(' ')
                .append(algorithm).append(' ')
                .append(digestType).append(' ')
                .append(new BigInteger(1, digest).toString(16).toUpperCase());
        return sb.toString();
    }

    private transient BigInteger digestBigIntCache;

    public BigInteger getDigestBigInteger() {
        if (digestBigIntCache == null) {
            digestBigIntCache = new BigInteger(1, digest);
        }
        return digestBigIntCache;
    }

    private transient String digestHexCache;

    public String getDigestHex() {
        if (digestHexCache == null) {
            digestHexCache = getDigestBigInteger().toString(16).toUpperCase();
        }
        return digestHexCache;
    }

    public boolean digestEquals(byte[] otherDigest) {
        return Arrays.equals(digest, otherDigest);
    }
}
