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
import java.util.HashMap;
import java.util.Map;

public class TLSA extends Data {

    private static final Map<Byte, CertUsage> CERT_USAGE_LUT = new HashMap<>();

    public enum CertUsage {

        caConstraint((byte) 0),
        serviceCertificateConstraint((byte) 1),
        trustAnchorAssertion((byte) 2),
        domainIssuedCertificate((byte) 3),
        ;

        public final byte byteValue;

        CertUsage(byte byteValue) {
            this.byteValue = byteValue;
            CERT_USAGE_LUT.put(byteValue, this);
        }
    }

    private static final Map<Byte, Selector> SELECTOR_LUT = new HashMap<>();

    public enum Selector {
        fullCertificate((byte) 0),
        subjectPublicKeyInfo((byte) 1),
        ;

        public final byte byteValue;

         Selector(byte byteValue) {
            this.byteValue = byteValue;
            SELECTOR_LUT.put(byteValue, this);
        }
    }

    private static final Map<Byte, MatchingType> MATCHING_TYPE_LUT = new HashMap<>();

    public enum MatchingType {
        noHash((byte) 0),
        sha256((byte) 1),
        sha512((byte) 2),
        ;

        public final byte byteValue;

        MatchingType(byte byteValue) {
            this.byteValue = byteValue;
            MATCHING_TYPE_LUT.put(byteValue, this);
        }
    }

    static {
        // Ensure that the LUTs are initialized.
        CertUsage.values();
        Selector.values();
        MatchingType.values();
    }

    /**
     * The provided association that will be used to match the certificate presented in
     * the TLS handshake.
     */
    public final byte certUsageByte;

    public final CertUsage certUsage;

    /**
     * Which part of the TLS certificate presented by the server will be matched against the
     * association data.
     */
    public final byte selectorByte;

    public final Selector selector;

    /**
     * How the certificate association is presented.
     */
    public final byte matchingTypeByte;

    public final MatchingType matchingType;

    /**
     * The "certificate association data" to be matched.
     */
    private final byte[] certificateAssociation;

    public static TLSA parse(DataInputStream dis, int length) throws IOException {
        byte certUsage = dis.readByte();
        byte selector = dis.readByte();
        byte matchingType = dis.readByte();
        byte[] certificateAssociation = new byte[length - 3];
        if (dis.read(certificateAssociation) != certificateAssociation.length) throw new IOException();
        return new TLSA(certUsage, selector, matchingType, certificateAssociation);
    }

    TLSA(byte certUsageByte, byte selectorByte, byte matchingTypeByte, byte[] certificateAssociation) {
        this.certUsageByte = certUsageByte;
        this.certUsage = CERT_USAGE_LUT.get(certUsageByte);

        this.selectorByte = selectorByte;
        this.selector = SELECTOR_LUT.get(selectorByte);

        this.matchingTypeByte = matchingTypeByte;
        this.matchingType = MATCHING_TYPE_LUT.get(matchingTypeByte);

        this.certificateAssociation = certificateAssociation;
    }

    @Override
    public Record.TYPE getType() {
        return Record.TYPE.TLSA;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeByte(certUsageByte);
        dos.writeByte(selectorByte);
        dos.writeByte(matchingTypeByte);
        dos.write(certificateAssociation);
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append(certUsageByte).append(' ')
                .append(selectorByte).append(' ')
                .append(matchingTypeByte).append(' ')
                .append(new BigInteger(1, certificateAssociation).toString(16)).toString();
    }

    public byte[] getCertificateAssociation() {
        return certificateAssociation.clone();
    }

    public boolean certificateAssociationEquals(byte[] otherCertificateAssociation) {
        return Arrays.equals(certificateAssociation, otherCertificateAssociation);
    }
}
