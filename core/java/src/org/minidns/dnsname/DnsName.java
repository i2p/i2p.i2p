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
package org.minidns.dnsname;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

import org.minidns.dnslabel.DnsLabel;
import org.minidns.idna.MiniDnsIdna;

/**
 * A DNS name, also called "domain name". A DNS name consists of multiple 'labels' and is subject to certain restrictions (see
 * for example <a href="https://tools.ietf.org/html/rfc3696#section-2">RFC 3696 § 2.</a>).
 * <p>
 * Instances of this class can be created by using {@link #from(String)}.
 * </p>
 * <p>
 * This class holds three representations of a DNS name: ACE, raw ACE and IDN. ACE (ASCII Compatible Encoding), which
 * can be accessed via {@link #ace}, represents mostly the data that got send over the wire. But since DNS names are
 * case insensitive, the ACE value is normalized to lower case. You can use {@link #getRawAce()} to get the raw ACE data
 * that was received, which possibly includes upper case characters. The IDN (Internationalized Domain Name), that is
 * the DNS name as it should be shown to the user, can be retrieved using {@link #asIdn()}.
 * </p>
 * More information about Internationalized Domain Names can be found at:
 * <ul>
 * <li><a href="https://unicode.org/reports/tr46/">UTS #46 - Unicode IDNA Compatibility Processing</a>
 * <li><a href="https://tools.ietf.org/html/rfc8753">RFC 8753 - Internationalized Domain Names for Applications (IDNA) Review for New Unicode Versions</a>
 * </ul>
 *
 * @see <a href="https://tools.ietf.org/html/rfc3696">RFC 3696</a>
 * @author Florian Schmaus
 *
 */
public final class DnsName implements CharSequence, Serializable, Comparable<DnsName> {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    /**
     * @see <a href="https://www.ietf.org/rfc/rfc3490.txt">RFC 3490 § 3.1 1.</a>
     */
    private static final String LABEL_SEP_REGEX = "[.\u3002\uFF0E\uFF61]";

    /**
     * @see <a href="https://tools.ietf.org/html/rfc1035">RFC 1035 § 2.3.4.</a>
     */
    static final int MAX_DNSNAME_LENGTH_IN_OCTETS = 255;

    public static final int MAX_LABELS = 128;

    public static final DnsName ROOT = new DnsName(".");

    public static final DnsName IN_ADDR_ARPA = new DnsName("in-addr.arpa");

    public static final DnsName IP6_ARPA = new DnsName("ip6.arpa");

    /**
     * Whether or not the DNS name is validated on construction.
     */
    public static boolean VALIDATE = true;

    /**
     * The DNS name in ASCII Compatible Encoding (ACE).
     */
    public final String ace;

    /**
     * The DNS name in raw format, i.e. as it was received from the remote server. This means that compared to
     * {@link #ace}, this String may not be lower-cased.
     */
    private final String rawAce;

    private transient byte[] bytes;

    private transient byte[] rawBytes;

    private transient String idn;

    private transient String domainpart;

    private transient String hostpart;

    /**
     * The labels in <b>reverse</b> order.
     */
    private transient DnsLabel[] labels;

    private transient DnsLabel[] rawLabels;

    private transient int hashCode;

    private int size = -1;

    private DnsName(String name) {
        this(name, true);
    }

    private DnsName(String name, boolean inAce) {
        if (name.isEmpty()) {
            rawAce = ROOT.rawAce;
        } else {
            final int nameLength = name.length();
            final int nameLastPos = nameLength - 1;

            // Strip potential trailing dot. N.B. that we require nameLength > 2, because we don't want to strip the one
            // character string containing only a single dot to the empty string.
            if (nameLength >= 2 && name.charAt(nameLastPos) == '.') {
                name = name.subSequence(0, nameLastPos).toString();
            }

            if (inAce) {
                // Name is already in ACE format.
                rawAce = name;
            } else {
                rawAce = MiniDnsIdna.toASCII(name);
            }
        }

        ace = rawAce.toLowerCase(Locale.US);

        if (!VALIDATE) {
            return;
        }

        // Validate the DNS name.
        validateMaxDnsnameLengthInOctets();
    }

    private DnsName(DnsLabel[] rawLabels, boolean validateMaxDnsnameLength) {
        this.rawLabels = rawLabels;
        this.labels = new DnsLabel[rawLabels.length];

        int size = 0;
        for (int i = 0; i < rawLabels.length; i++) {
            size += rawLabels[i].length() + 1;
            labels[i] = rawLabels[i].asLowercaseVariant();
        }

        rawAce = labelsToString(rawLabels, size);
        ace    = labelsToString(labels,    size);

        // The following condition is deliberately designed that VALIDATE=false causes the validation to be skipped even
        // if validateMaxDnsnameLength is set to true. There is no need to validate even if this constructor is called
        // with validateMaxDnsnameLength set to true if VALIDATE is globally set to false.
        if (!validateMaxDnsnameLength || !VALIDATE) {
            return;
        }

        validateMaxDnsnameLengthInOctets();
    }

    private static String labelsToString(DnsLabel[] labels, int stringLength) {
        StringBuilder sb = new StringBuilder(stringLength);
        for (int i = labels.length - 1; i >= 0; i--) {
            sb.append(labels[i]).append('.');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private void validateMaxDnsnameLengthInOctets() {
        setBytesIfRequired();
        if (bytes.length > MAX_DNSNAME_LENGTH_IN_OCTETS) {
            throw new InvalidDnsNameException.DNSNameTooLongException(ace, bytes);
        }
    }

    public void writeToStream(OutputStream os) throws IOException {
        setBytesIfRequired();
        os.write(bytes);
    }

    /**
     * Serialize a domain name under IDN rules.
     *
     * @return The binary domain name representation.
     */
    public byte[] getBytes() {
        setBytesIfRequired();
        return bytes.clone();
    }

    public byte[] getRawBytes() {
        if (rawBytes == null) {
            setLabelsIfRequired();
            rawBytes = toBytes(rawLabels);
        }

        return rawBytes.clone();
    }

    private void setBytesIfRequired() {
        if (bytes != null)
            return;

        setLabelsIfRequired();
        bytes = toBytes(labels);
    }

    private static byte[] toBytes(DnsLabel[] labels) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        for (int i = labels.length - 1; i >= 0; i--) {
            labels[i].writeToBoas(baos);
        }

        baos.write(0);

        assert baos.size() <= MAX_DNSNAME_LENGTH_IN_OCTETS;

        return baos.toByteArray();
    }

    private void setLabelsIfRequired() {
        if (labels != null && rawLabels != null) return;

        if (isRootLabel()) {
            rawLabels = labels = new DnsLabel[0];
            return;
        }

        labels = getLabels(ace);
        rawLabels = getLabels(rawAce);
    }

    private static DnsLabel[] getLabels(String ace) {
        String[] labels = ace.split(LABEL_SEP_REGEX, MAX_LABELS);

        // Reverse the labels, so that 'foo, example, org' becomes 'org, example, foo'.
        for (int i = 0; i < labels.length / 2; i++) {
            String t = labels[i];
            int j = labels.length - i - 1;
            labels[i] = labels[j];
            labels[j] = t;
        }

        try {
            return DnsLabel.from(labels);
        } catch (DnsLabel.LabelToLongException e) {
            throw new InvalidDnsNameException.LabelTooLongException(ace, e.label);
        }
    }

    public String getRawAce() {
        return rawAce;
    }

    public String asIdn() {
        if (idn != null)
            return idn;

        idn = MiniDnsIdna.toUnicode(ace);
        return idn;
    }

    /**
     * Domainpart in ACE representation.
     *
     * @return the domainpart in ACE representation.
     */
    public String getDomainpart() {
        setHostnameAndDomainpartIfRequired();
        return domainpart;
    }

    /**
     * Hostpart in ACE representation.
     *
     * @return the hostpart in ACE representation.
     */
    public String getHostpart() {
        setHostnameAndDomainpartIfRequired();
        return hostpart;
    }

    public DnsLabel getHostpartLabel() {
        setLabelsIfRequired();
        return labels[labels.length];
    }

    private void setHostnameAndDomainpartIfRequired() {
        if (hostpart != null) return;

        String[] parts = ace.split(LABEL_SEP_REGEX, 2);
        hostpart = parts[0];
        if (parts.length > 1) {
            domainpart = parts[1];
        } else {
            domainpart = "";
        }
    }

    public int size() {
        if (size < 0) {
            if (isRootLabel()) {
                size = 1;
            } else {
                size = ace.length() + 2;
            }
        }
        return size;
    }

    @Override
    public int length() {
        return ace.length();
    }

    @Override
    public char charAt(int index) {
        return ace.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return ace.subSequence(start, end);
    }

    @Override
    public String toString() {
        return ace;
    }

    public static DnsName from(CharSequence name) {
        return from(name.toString());
    }

    public static DnsName from(String name) {
        return new DnsName(name, false);
    }

    /**
     * Create a DNS name by "concatenating" the child under the parent name. The child can also be seen as the "left"
     * part of the resulting DNS name and the parent is the "right" part.
     * <p>
     * For example using "i.am.the.child" as child and "of.this.parent.example" as parent, will result in a DNS name:
     * "i.am.the.child.of.this.parent.example".
     * </p>
     *
     * @param child the child DNS name.
     * @param parent the parent DNS name.
     * @return the resulting of DNS name.
     */
    public static DnsName from(DnsName child, DnsName parent) {
        child.setLabelsIfRequired();
        parent.setLabelsIfRequired();

        DnsLabel[] rawLabels = new DnsLabel[child.rawLabels.length + parent.rawLabels.length];
        System.arraycopy(parent.rawLabels, 0, rawLabels, 0, parent.rawLabels.length);
        System.arraycopy(child.rawLabels, 0, rawLabels, parent.rawLabels.length, child.rawLabels.length);
        return new DnsName(rawLabels, true);
    }

    public static DnsName from(DnsLabel child, DnsName parent) {
        parent.setLabelsIfRequired();

        DnsLabel[] rawLabels = new DnsLabel[parent.rawLabels.length + 1];
        System.arraycopy(parent.rawLabels, 0, rawLabels, 0, parent.rawLabels.length);
        rawLabels[rawLabels.length] = child;
        return new DnsName(rawLabels, true);
    }

    public static DnsName from(DnsLabel grandchild, DnsLabel child, DnsName parent) {
        parent.setBytesIfRequired();

        DnsLabel[] rawLabels = new DnsLabel[parent.rawLabels.length + 2];
        System.arraycopy(parent.rawLabels, 0, rawLabels, 0, parent.rawLabels.length);
        rawLabels[parent.rawLabels.length] = child;
        rawLabels[parent.rawLabels.length + 1] = grandchild;
        return new DnsName(rawLabels, true);
    }

    public static DnsName from(DnsName... nameComponents) {
        int labelCount = 0;
        for (DnsName component : nameComponents) {
            component.setLabelsIfRequired();
            labelCount += component.rawLabels.length;
        }

        DnsLabel[] rawLabels = new DnsLabel[labelCount];
        int destLabelPos = 0;
        for (int i = nameComponents.length - 1; i >= 0; i--) {
            DnsName component = nameComponents[i];
            System.arraycopy(component.rawLabels, 0, rawLabels, destLabelPos, component.rawLabels.length);
            destLabelPos += component.rawLabels.length;
        }

        return new DnsName(rawLabels, true);
    }

    public static DnsName from(String[] parts) {
        DnsLabel[] rawLabels = DnsLabel.from(parts);

        return new DnsName(rawLabels, true);
    }

    /**
     * Parse a domain name starting at the current offset and moving the input
     * stream pointer past this domain name (even if cross references occure).
     *
     * @param dis  The input stream.
     * @param data The raw data (for cross references).
     * @return The domain name string.
     * @throws IOException Should never happen.
     */
    public static DnsName parse(DataInputStream dis, byte[] data)
            throws IOException {
        int c = dis.readUnsignedByte();
        if ((c & 0xc0) == 0xc0) {
            c = ((c & 0x3f) << 8) + dis.readUnsignedByte();
            HashSet<Integer> jumps = new HashSet<Integer>();
            jumps.add(c);
            return parse(data, c, jumps);
        }
        if (c == 0) {
            return DnsName.ROOT;
        }
        byte[] b = new byte[c];
        dis.readFully(b);

        String childLabelString = new String(b, StandardCharsets.US_ASCII);
        DnsName child = new DnsName(childLabelString);

        DnsName parent = parse(dis, data);
        return DnsName.from(child, parent);
    }

    /**
     * Parse a domain name starting at the given offset.
     *
     * @param data   The raw data.
     * @param offset The offset.
     * @param jumps  The list of jumps (by now).
     * @return The parsed domain name.
     * @throws IllegalStateException on cycles.
     */
    private static DnsName parse(byte[] data, int offset, HashSet<Integer> jumps)
            throws IllegalStateException {
        int c = data[offset] & 0xff;
        if ((c & 0xc0) == 0xc0) {
            c = ((c & 0x3f) << 8) + (data[offset + 1] & 0xff);
            if (jumps.contains(c)) {
                throw new IllegalStateException("Cyclic offsets detected.");
            }
            jumps.add(c);
            return parse(data, c, jumps);
        }
        if (c == 0) {
            return DnsName.ROOT;
        }

        String childLabelString = new String(data, offset + 1, c, StandardCharsets.US_ASCII);
        DnsName child = new DnsName(childLabelString);

        DnsName parent = parse(data, offset + 1 + c, jumps);
        return DnsName.from(child, parent);
    }

    @Override
    public int compareTo(DnsName other) {
        return ace.compareTo(other.ace);
    }

    @Override
    public boolean equals(Object other) {
        if (other == null) return false;

        if (other instanceof DnsName) {
            DnsName otherDnsName = (DnsName) other;
            setBytesIfRequired();
            otherDnsName.setBytesIfRequired();
            return Arrays.equals(bytes, otherDnsName.bytes);
        }

        return false;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0 && !isRootLabel()) {
            setBytesIfRequired();
            hashCode = Arrays.hashCode(bytes);
        }
        return hashCode;
    }

    public boolean isDirectChildOf(DnsName parent) {
        setLabelsIfRequired();
        parent.setLabelsIfRequired();
        int parentLabelsCount = parent.labels.length;

        if (labels.length - 1 != parentLabelsCount)
            return false;

        for (int i = 0; i < parent.labels.length; i++) {
            if (!labels[i].equals(parent.labels[i]))
                return false;
        }

        return true;
    }

    public boolean isChildOf(DnsName parent) {
        setLabelsIfRequired();
        parent.setLabelsIfRequired();

        if (labels.length < parent.labels.length)
            return false;

        for (int i = 0; i < parent.labels.length; i++) {
            if (!labels[i].equals(parent.labels[i]))
                return false;
        }

        return true;
    }

    public int getLabelCount() {
        setLabelsIfRequired();
        return labels.length;
    }

    /**
     * Get a copy of the labels of this DNS name. The resulting array will contain the labels in reverse order, that is,
     * the top-level domain will be at res[0].
     *
     * @return an array of the labels in reverse order.
     */
    public DnsLabel[] getLabels() {
        setLabelsIfRequired();
        return labels.clone();
    }


    public DnsLabel getLabel(int labelNum) {
        setLabelsIfRequired();
        return labels[labelNum];
    }

    /**
     * Get a copy of the raw labels of this DNS name. The resulting array will contain the labels in reverse order, that is,
     * the top-level domain will be at res[0].
     *
     * @return an array of the raw labels in reverse order.
     */
    public DnsLabel[] getRawLabels() {
        setLabelsIfRequired();
        return rawLabels.clone();
    }

    public DnsName stripToLabels(int labelCount) {
        setLabelsIfRequired();

        if (labelCount > labels.length) {
            throw new IllegalArgumentException();
        }

        if (labelCount == labels.length) {
            return this;
        }

        if (labelCount == 0) {
            return ROOT;
        }

        DnsLabel[] stripedLabels = Arrays.copyOfRange(rawLabels, 0, labelCount);

        return new DnsName(stripedLabels, false);
    }

    /**
     * Return the parent of this DNS label. Will return the root label if this label itself is the root label (because there is no parent of root).
     * <p>
     * For example:
     * </p>
     * <ul>
     *  <li><code>"foo.bar.org".getParent() == "bar.org"</code></li>
     *  <li><code> ".".getParent() == "."</code></li>
     * </ul>
     * @return the parent of this DNS label.
     */
    public DnsName getParent() {
        if (isRootLabel()) return ROOT;
        return stripToLabels(getLabelCount() - 1);
    }

    public boolean isRootLabel() {
        return ace.isEmpty() || ace.equals(".");
    }
}
