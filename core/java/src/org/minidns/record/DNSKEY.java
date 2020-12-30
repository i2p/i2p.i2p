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

import org.minidns.constants.DnssecConstants.SignatureAlgorithm;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * DNSKEY record payload.
 */
public class DNSKEY extends Data {
    /**
     * Whether the key should be used as a secure entry point key.
     *
     * see RFC 3757
     */
    public static final short FLAG_SECURE_ENTRY_POINT = 0x1;

    /**
     * Whether the record holds a revoked key.
     */
    public static final short FLAG_REVOKE = 0x80;

    /**
     * Whether the record holds a DNS zone key.
     */
    public static final short FLAG_ZONE = 0x100;

    /**
     * Use the protocol defined in RFC 4034.
     */
    public static final byte PROTOCOL_RFC4034 = 3;

    /**
     * Bitmap of flags: {@link #FLAG_SECURE_ENTRY_POINT}, {@link #FLAG_REVOKE}, {@link #FLAG_ZONE}.
     *
     * @see <a href="https://www.iana.org/assignments/dnskey-flags/dnskey-flags.xhtml">IANA - DNSKEY RR Flags</a>
     */
    public final short flags;

    /**
     * Must be {@link #PROTOCOL_RFC4034}.
     */
    public final byte protocol;

    /**
     * The public key's cryptographic algorithm used.
     *
     */
    public final SignatureAlgorithm algorithm;

    /**
     * The byte value of the public key's cryptographic algorithm used.
     *
     */
    public final byte algorithmByte;

    /**
     * The public key material. The format depends on the algorithm of the key being stored.
     */
    private final byte[] key;

    /**
     * This DNSKEY's key tag. Calculated just-in-time when using {@link #getKeyTag()}
     */
    private transient Integer keyTag;

    public static DNSKEY parse(DataInputStream dis, int length) throws IOException {
        short flags = dis.readShort();
        byte protocol = dis.readByte();
        byte algorithm = dis.readByte();
        byte[] key = new byte[length - 4];
        dis.readFully(key);
        return new DNSKEY(flags, protocol, algorithm, key);
    }

    private DNSKEY(short flags, byte protocol, SignatureAlgorithm algorithm, byte algorithmByte, byte[] key) {
        this.flags = flags;
        this.protocol = protocol;

        assert algorithmByte == (algorithm != null ? algorithm.number : algorithmByte);
        this.algorithmByte = algorithmByte;
        this.algorithm = algorithm != null ? algorithm : SignatureAlgorithm.forByte(algorithmByte);

        this.key = key;
    }

    public DNSKEY(short flags, byte protocol, byte algorithm, byte[] key) {
        this(flags, protocol, SignatureAlgorithm.forByte(algorithm), key);
    }

    public DNSKEY(short flags, byte protocol, SignatureAlgorithm algorithm, byte[] key) {
        this(flags, protocol, algorithm, algorithm.number, key);
    }

    @Override
    public TYPE getType() {
        return TYPE.DNSKEY;
    }

    /**
     * Retrieve the key tag identifying this DNSKEY.
     * The key tag is used within the DS and RRSIG record to distinguish multiple keys for the same name.
     *
     * This implementation is based on the reference implementation shown in RFC 4034 Appendix B.
     *
     * @return this DNSKEY's key tag
     */
    public /* unsigned short */ int getKeyTag() {
        if (keyTag == null) {
            byte[] recordBytes = toByteArray();
            long ac = 0;

            for (int i = 0; i < recordBytes.length; ++i) {
                ac += ((i & 1) > 0) ? recordBytes[i] & 0xFFL : ((recordBytes[i] & 0xFFL) << 8);
            }
            ac += (ac >> 16) & 0xFFFF;
            keyTag = (int) (ac & 0xFFFF);
        }
        return keyTag;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeShort(flags);
        dos.writeByte(protocol);
        dos.writeByte(algorithm.number);
        dos.write(key);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(flags).append(' ')
                .append(protocol).append(' ')
                .append(algorithm).append(' ')
                .append(Base64.encodeToString(key));
        return sb.toString();
    }

    public int getKeyLength() {
        return key.length;
    }

    public byte[] getKey() {
        return key.clone();
    }

    public DataInputStream getKeyAsDataInputStream() {
        return new DataInputStream(new ByteArrayInputStream(key));
    }

    private transient String keyBase64Cache;

    public String getKeyBase64() {
        if (keyBase64Cache == null) {
            keyBase64Cache = Base64.encodeToString(key);
        }
        return keyBase64Cache;
    }

    private transient BigInteger keyBigIntegerCache;

    public BigInteger getKeyBigInteger() {
        if (keyBigIntegerCache == null) {
            keyBigIntegerCache = new BigInteger(key);
        }
        return keyBigIntegerCache;
    }

    public boolean keyEquals(byte[] otherKey) {
        return Arrays.equals(key, otherKey);
    }

    public boolean isSecureEntryPoint() {
        return (flags & FLAG_SECURE_ENTRY_POINT) == 1;
    }
}
