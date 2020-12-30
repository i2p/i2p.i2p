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
import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.TYPE;
import org.minidns.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * RRSIG record payload.
 */
public class RRSIG extends Data {

    /**
     * The type of RRset covered by this signature.
     */
    public final TYPE typeCovered;

    /**
     * The cryptographic algorithm used to create the signature.
     */
    public final SignatureAlgorithm algorithm;

    /**
     * The cryptographic algorithm used to create the signature.
     */
    public final byte algorithmByte;

    /**
     * The number of labels in the original RRSIG RR owner name.
     */
    public final byte labels;

    /**
     * The TTL of the covered RRset.
     */
    public final long /* unsigned int */ originalTtl;

    /**
     * The date and time this RRSIG records expires.
     */
    public final Date signatureExpiration;

    /**
     * The date and time this RRSIG records starts to be valid.
     */
    public final Date signatureInception;

    /**
     * The key tag value of the DNSKEY RR that validates this signature.
     */
    public final int /* unsigned short */  keyTag;

    /**
     * The owner name of the DNSKEY RR that a validator is supposed to use.
     */
    public final DnsName signerName;

    /**
     * Signature that covers RRSIG RDATA (excluding the signature field) and RRset data.
     */
    private final byte[] signature;

    public static RRSIG parse(DataInputStream dis, byte[] data, int length) throws IOException {
        TYPE typeCovered = TYPE.getType(dis.readUnsignedShort());
        byte algorithm = dis.readByte();
        byte labels = dis.readByte();
        long originalTtl = dis.readInt() & 0xFFFFFFFFL;
        Date signatureExpiration = new Date((dis.readInt() & 0xFFFFFFFFL) * 1000);
        Date signatureInception = new Date((dis.readInt() & 0xFFFFFFFFL) * 1000);
        int keyTag = dis.readUnsignedShort();
        DnsName signerName = DnsName.parse(dis, data);
        int sigSize = length - signerName.size() - 18;
        byte[] signature = new byte[sigSize];
        if (dis.read(signature) != signature.length) throw new IOException();
        return new RRSIG(typeCovered, null, algorithm, labels, originalTtl, signatureExpiration, signatureInception, keyTag, signerName,
                signature);
    }

    private  RRSIG(TYPE typeCovered, SignatureAlgorithm algorithm, byte algorithmByte, byte labels, long originalTtl, Date signatureExpiration, 
            Date signatureInception, int keyTag, DnsName signerName, byte[] signature) {
        this.typeCovered = typeCovered;

        assert algorithmByte == (algorithm != null ? algorithm.number : algorithmByte);
        this.algorithmByte = algorithmByte;
        this.algorithm = algorithm != null ? algorithm : SignatureAlgorithm.forByte(algorithmByte);

        this.labels = labels;
        this.originalTtl = originalTtl;
        this.signatureExpiration = signatureExpiration;
        this.signatureInception = signatureInception;
        this.keyTag = keyTag;
        this.signerName = signerName;
        this.signature = signature;
    }

    public RRSIG(TYPE typeCovered, int algorithm, byte labels, long originalTtl, Date signatureExpiration, 
            Date signatureInception, int keyTag, DnsName signerName, byte[] signature) {
            this(typeCovered, null, (byte) algorithm, labels, originalTtl, signatureExpiration, signatureInception, keyTag, signerName, signature);
    }

    public RRSIG(TYPE typeCovered, int algorithm, byte labels, long originalTtl, Date signatureExpiration, 
            Date signatureInception, int keyTag, String signerName, byte[] signature) {
            this(typeCovered, null, (byte) algorithm, labels, originalTtl, signatureExpiration, signatureInception, keyTag, DnsName.from(signerName), signature);
    }

    public RRSIG(TYPE typeCovered, SignatureAlgorithm algorithm, byte labels,
            long originalTtl, Date signatureExpiration, Date signatureInception,
            int keyTag, DnsName signerName, byte[] signature) {
        this(typeCovered, algorithm.number, labels, originalTtl, signatureExpiration, signatureInception,
                keyTag, signerName, signature);
    }

    public RRSIG(TYPE typeCovered, SignatureAlgorithm algorithm, byte labels,
            long originalTtl, Date signatureExpiration, Date signatureInception,
            int keyTag, String signerName, byte[] signature) {
        this(typeCovered, algorithm.number, labels, originalTtl, signatureExpiration, signatureInception,
                keyTag, DnsName.from(signerName), signature);
    }

    public byte[] getSignature() {
        return signature.clone();
    }

    public DataInputStream getSignatureAsDataInputStream() {
        return new DataInputStream(new ByteArrayInputStream(signature));
    }

    public int getSignatureLength() {
        return signature.length;
    }

    private transient String base64SignatureCache;

    public String getSignatureBase64() {
        if (base64SignatureCache == null) {
            base64SignatureCache = Base64.encodeToString(signature);
        }
        return base64SignatureCache;
    }

    @Override
    public TYPE getType() {
        return TYPE.RRSIG;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        writePartialSignature(dos);
        dos.write(signature);
    }

    public void writePartialSignature(DataOutputStream dos) throws IOException {
        dos.writeShort(typeCovered.getValue());
        dos.writeByte(algorithmByte);
        dos.writeByte(labels);
        dos.writeInt((int) originalTtl);
        dos.writeInt((int) (signatureExpiration.getTime() / 1000));
        dos.writeInt((int) (signatureInception.getTime() / 1000));
        dos.writeShort(keyTag);
        signerName.writeToStream(dos);
    }

    @Override
    public String toString() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        StringBuilder sb = new StringBuilder()
                .append(typeCovered).append(' ')
                .append(algorithm).append(' ')
                .append(labels).append(' ')
                .append(originalTtl).append(' ')
                .append(dateFormat.format(signatureExpiration)).append(' ')
                .append(dateFormat.format(signatureInception)).append(' ')
                .append(keyTag).append(' ')
                .append(signerName).append(". ")
                .append(getSignatureBase64());
        return sb.toString();
    }
}
