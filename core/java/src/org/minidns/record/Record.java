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
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.minidns.dnsmessage.DnsMessage;
import org.minidns.dnsmessage.Question;
import org.minidns.dnsname.DnsName;

/**
 * A generic DNS record.
 */
public final class Record<D extends Data> {

    /**
     * The resource record type.
     * 
     * @see <a href=
     *      "http://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4">
     *      IANA DNS Parameters - Resource Record (RR) TYPEs</a>
     */
    public enum TYPE {
        UNKNOWN(-1),
        A(1, A.class),
        NS(2, NS.class),
        MD(3),
        MF(4),
        CNAME(5, CNAME.class),
        SOA(6, SOA.class),
        MB(7),
        MG(8),
        MR(9),
        NULL(10),
        WKS(11),
        PTR(12, PTR.class),
        HINFO(13),
        MINFO(14),
        MX(15, MX.class),
        TXT(16, TXT.class),
        RP(17),
        AFSDB(18),
        X25(19),
        ISDN(20),
        RT(21),
        NSAP(22),
        NSAP_PTR(23),
        SIG(24),
        KEY(25),
        PX(26),
        GPOS(27),
        AAAA(28, AAAA.class),
        LOC(29),
        NXT(30),
        EID(31),
        NIMLOC(32),
        SRV(33, SRV.class),
        ATMA(34),
        NAPTR(35),
        KX(36),
        CERT(37),
        A6(38),
        DNAME(39, DNAME.class),
        SINK(40),
        OPT(41, OPT.class),
        APL(42),
        DS(43, DS.class),
        SSHFP(44),
        IPSECKEY(45),
        RRSIG(46, RRSIG.class),
        NSEC(47, NSEC.class),
        DNSKEY(48, DNSKEY.class),
        DHCID(49),
        NSEC3(50, NSEC3.class),
        NSEC3PARAM(51, NSEC3PARAM.class),
        TLSA(52, TLSA.class),
        HIP(55),
        NINFO(56),
        RKEY(57),
        TALINK(58),
        CDS(59),
        CDNSKEY(60),
        OPENPGPKEY(61, OPENPGPKEY.class),
        CSYNC(62),
        SPF(99),
        UINFO(100),
        UID(101),
        GID(102),
        UNSPEC(103),
        NID(104),
        L32(105),
        L64(106),
        LP(107),
        EUI48(108),
        EUI64(109),
        TKEY(249),
        TSIG(250),
        IXFR(251),
        AXFR(252),
        MAILB(253),
        MAILA(254),
        ANY(255),
        URI(256),
        CAA(257),
        TA(32768),
        DLV(32769, DLV.class),
        ;

        /**
         * The value of this DNS record type.
         */
        private final int value;

        private final Class<?> dataClass;

        /**
         * Internal lookup table to map values to types.
         */
        private static final Map<Integer, TYPE> INVERSE_LUT = new HashMap<>();

        private static final Map<Class<?>, TYPE> DATA_LUT = new HashMap<>();

        static {
            // Initialize the reverse lookup table.
            for (TYPE t : TYPE.values()) {
                INVERSE_LUT.put(t.getValue(), t);
                if (t.dataClass != null) {
                    DATA_LUT.put(t.dataClass, t);
                }
            }
        }

        /**
         * Create a new record type.
         * 
         * @param value The binary value of this type.
         */
        TYPE(int value) {
            this(value, null);
        }

        /**
         * Create a new record type.
         *
         * @param <D> The class for this type.
         * @param dataClass The class for this type.
         * @param value The binary value of this type.
         */
        <D extends Data> TYPE(int value, Class<D> dataClass) {
            this.value = value;
            this.dataClass = dataClass;
        }

        /**
         * Retrieve the binary value of this type.
         * @return The binary value.
         */
        public int getValue() {
            return value;
        }

        /**
         * Get the {@link Data} class for this type.
         *
         * @param <D> The class for this type.
         * @return the {@link Data} class for this type.
         */
        @SuppressWarnings("unchecked")
        public <D extends Data> Class<D> getDataClass() {
            return (Class<D>) dataClass;
        }

        /**
         * Retrieve the symbolic type of the binary value.
         * @param value The binary type value.
         * @return The symbolic tpye.
         */
        public static TYPE getType(int value) {
            TYPE type = INVERSE_LUT.get(value);
            if (type == null) return UNKNOWN;
            return type;
        }

        /**
         * Retrieve the type for a given {@link Data} class.
         *
         * @param <D> The class for this type.
         * @param dataClass the class to lookup the type for.
         * @return the type for the given data class.
         */
        public static <D extends Data> TYPE getType(Class<D> dataClass) {
            return DATA_LUT.get(dataClass);
        }
    }

    /**
     * The symbolic class of a DNS record (usually {@link CLASS#IN} for Internet).
     *
     * @see <a href="http://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-2">IANA Domain Name System (DNS) Parameters - DNS CLASSes</a>
     */
    public enum CLASS {

        /**
         * The Internet class. This is the most common class used by todays DNS systems.
         */
        IN(1),

        /**
         * The Chaos class.
         */
        CH(3),

        /**
         * The Hesiod class.
         */
        HS(4),
        NONE(254),
        ANY(255);

        /**
         * Internal reverse lookup table to map binary class values to symbolic
         * names.
         */
        private static final HashMap<Integer, CLASS> INVERSE_LUT =
                                            new HashMap<Integer, CLASS>();

        static {
            // Initialize the interal reverse lookup table.
            for (CLASS c : CLASS.values()) {
                INVERSE_LUT.put(c.getValue(), c);
            }
        }

        /**
         * The binary value of this dns class.
         */
        private final int value;

        /**
         * Create a new DNS class based on a binary value.
         * @param value The binary value of this DNS class.
         */
        CLASS(int value) {
            this.value = value;
        }

        /**
         * Retrieve the binary value of this DNS class.
         * @return The binary value of this DNS class.
         */
        public int getValue() {
            return value;
        }

        /**
         * Retrieve the symbolic DNS class for a binary class value.
         * @param value The binary DNS class value.
         * @return The symbolic class instance.
         */
        public static CLASS getClass(int value) {
            return INVERSE_LUT.get(value);
        }

    }

    /**
     * The generic name of this record.
     */
    public final DnsName name;

    /**
     * The type (and payload type) of this record.
     */
    public final TYPE type;

    /**
     * The record class (usually CLASS.IN).
     */
    public final CLASS clazz;

    /**
     * The value of the class field of a RR.
     * 
     * According to RFC 2671 (OPT RR) this is not necessarily representable
     * using clazz field and unicastQuery bit
     */
    public final int clazzValue;

    /**
     * The ttl of this record.
     */
    public final long ttl;

    /**
     * The payload object of this record.
     */
    public final D payloadData;

    /**
     * MDNS defines the highest bit of the class as the unicast query bit.
     */
    public final boolean unicastQuery;

    /**
     * Parse a given record based on the full message data and the current
     * stream position.
     *
     * @param dis The DataInputStream positioned at the first record byte.
     * @param data The full message data.
     * @return the record which was parsed.
     * @throws IOException In case of malformed replies.
     */
    public static Record<Data> parse(DataInputStream dis, byte[] data) throws IOException {
        DnsName name = DnsName.parse(dis, data);
        int typeValue = dis.readUnsignedShort();
        TYPE type = TYPE.getType(typeValue);
        int clazzValue = dis.readUnsignedShort();
        CLASS clazz = CLASS.getClass(clazzValue & 0x7fff);
        boolean unicastQuery = (clazzValue & 0x8000) > 0;
        long ttl = (((long) dis.readUnsignedShort()) << 16) +
                   dis.readUnsignedShort();
        int payloadLength = dis.readUnsignedShort();
        Data payloadData;
        switch (type) {
            case SOA:
                payloadData = SOA.parse(dis, data);
                break;
            case SRV:
                payloadData = SRV.parse(dis, data);
                break;
            case MX:
                payloadData = MX.parse(dis, data);
                break;
            case AAAA:
                payloadData = AAAA.parse(dis);
                break;
            case A:
                payloadData = A.parse(dis);
                break;
            case NS:
                payloadData = NS.parse(dis, data);
                break;
            case CNAME:
                payloadData = CNAME.parse(dis, data);
                break;
            case DNAME:
                payloadData = DNAME.parse(dis, data);
                break;
            case PTR:
                payloadData = PTR.parse(dis, data);
                break;
            case TXT:
                payloadData = TXT.parse(dis, payloadLength);
                break;
            case OPT:
                payloadData = OPT.parse(dis, payloadLength);
                break;
            case DNSKEY:
                payloadData = DNSKEY.parse(dis, payloadLength);
                break;
            case RRSIG:
                payloadData = RRSIG.parse(dis, data, payloadLength);
                break;
            case DS:
                payloadData = DS.parse(dis, payloadLength);
                break;
            case NSEC:
                payloadData = NSEC.parse(dis, data, payloadLength);
                break;
            case NSEC3:
                payloadData = NSEC3.parse(dis, payloadLength);
                break;
            case NSEC3PARAM:
                payloadData = NSEC3PARAM.parse(dis);
                break;
            case TLSA:
                payloadData = TLSA.parse(dis, payloadLength);
                break;
            case OPENPGPKEY:
                payloadData = OPENPGPKEY.parse(dis, payloadLength);
                break;
            case DLV:
                payloadData = DLV.parse(dis, payloadLength);
                break;
            case UNKNOWN:
            default:
                payloadData = UNKNOWN.parse(dis, payloadLength, type);
                break;
        }
        return new Record<>(name, type, clazz, clazzValue, ttl, payloadData, unicastQuery);
    }

    public Record(DnsName name, TYPE type, CLASS clazz, long ttl, D payloadData, boolean unicastQuery) {
        this(name, type, clazz, clazz.getValue() + (unicastQuery ? 0x8000 : 0), ttl, payloadData, unicastQuery);
    }

    public Record(String name, TYPE type, CLASS clazz, long ttl, D payloadData, boolean unicastQuery) {
        this(DnsName.from(name), type, clazz, ttl, payloadData, unicastQuery);
    }

    public Record(String name, TYPE type, int clazzValue, long ttl, D payloadData) {
        this(DnsName.from(name), type, CLASS.NONE, clazzValue, ttl, payloadData, false);
    }

    public Record(DnsName name, TYPE type, int clazzValue, long ttl, D payloadData) {
        this(name, type, CLASS.NONE, clazzValue, ttl, payloadData, false);
    }

    private Record(DnsName name, TYPE type, CLASS clazz, int clazzValue, long ttl, D payloadData, boolean unicastQuery) {
        this.name = name;
        this.type = type;
        this.clazz = clazz;
        this.clazzValue = clazzValue;
        this.ttl = ttl;
        this.payloadData = payloadData;
        this.unicastQuery = unicastQuery;
    }

    public void toOutputStream(OutputStream outputStream) throws IOException {
        if (payloadData == null) {
            throw new IllegalStateException("Empty Record has no byte representation");
        }

        DataOutputStream dos = new DataOutputStream(outputStream);

        name.writeToStream(dos);
        dos.writeShort(type.getValue());
        dos.writeShort(clazzValue);
        dos.writeInt((int) ttl);

        dos.writeShort(payloadData.length());
        payloadData.toOutputStream(dos);
    }

    private transient byte[] bytes;

    public byte[] toByteArray() {
        if (bytes == null) {
            int totalSize = name.size()
                    + 10 // 2 byte short type + 2 byte short classValue + 4 byte int ttl + 2 byte short payload length.
                    + payloadData.length();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(totalSize);
            DataOutputStream dos = new DataOutputStream(baos);
            try {
                toOutputStream(dos);
            } catch (IOException e) {
                // Should never happen.
                throw new AssertionError(e);
            }
            bytes = baos.toByteArray();
        }
        return bytes.clone();
    }

    /**
     * Retrieve a textual representation of this resource record.
     * @return String
     */
    @Override
    public String toString() {
        return name.getRawAce() + ".\t" + ttl + '\t' + clazz + '\t' + type + '\t' + payloadData;
    }

    /**
     * Check if this record answers a given query.
     * @param q The query.
     * @return True if this record is a valid answer.
     */
    public boolean isAnswer(Question q) {
        return ((q.type == type) || (q.type == TYPE.ANY)) &&
               ((q.clazz == clazz) || (q.clazz == CLASS.ANY)) &&
               q.name.equals(name);
    }

    /**
     * See if this query/response was a unicast query (highest class bit set).
     * @return True if it is a unicast query/response record.
     */
    public boolean isUnicastQuery() {
        return unicastQuery;
    }

    /**
     * The payload data, usually a subclass of data (A, AAAA, CNAME, ...).
     * @return The payload data.
     */
    public D getPayload() {
        return payloadData;
    }

    /**
     * Retrieve the record ttl.
     * @return The record ttl.
     */
    public long getTtl() {
        return ttl;
    }

    /**
     * Get the question asking for this resource record. This will return <code>null</code> if the record is not retrievable, i.e.
     * {@link TYPE#OPT}.
     *
     * @return the question for this resource record or <code>null</code>.
     */
    public Question getQuestion() {
        switch (type) {
        case OPT:
            // OPT records are not retrievable.
            return null;
        case RRSIG:
            RRSIG rrsig = (RRSIG) payloadData;
            return new Question(name, rrsig.typeCovered, clazz);
        default:
            return new Question(name, type, clazz);
        }
    }

    public DnsMessage.Builder getQuestionMessage() {
        Question question = getQuestion();
        if (question == null) {
            return null;
        }
        return question.asMessageBuilder();
    }

    private transient Integer hashCodeCache;

    @Override
    public int hashCode() {
        if (hashCodeCache == null) {
            int hashCode = 1;
            hashCode = 37 * hashCode + name.hashCode();
            hashCode = 37 * hashCode + type.hashCode();
            hashCode = 37 * hashCode + clazz.hashCode();
            hashCode = 37 * hashCode + payloadData.hashCode();
            hashCodeCache = hashCode;
        }
        return hashCodeCache;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Record)) {
            return false;
        }
        if (other == this) {
            return true;
        }
        Record<?> otherRecord = (Record<?>) other;
        if (!name.equals(otherRecord.name)) return false;
        if (type != otherRecord.type) return false;
        if (clazz != otherRecord.clazz) return false;
        // Note that we do not compare the TTL here, since we consider two Records with everything but the TTL equal to
        // be equal too.
        if (!payloadData.equals(otherRecord.payloadData)) return false;

        return true;
    }

    /**
     * Return the record if possible as record with the given {@link Data} class. If the record does not hold payload of
     * the given data class type, then {@code null} will be returned.
     *
     * @param dataClass a class of the {@link Data} type.
     * @param <E> a subtype of {@link Data}.
     * @return the record with a specialized payload type or {@code null}.
     * @see #as(Class)
     */
    @SuppressWarnings("unchecked")
    public <E extends Data> Record<E> ifPossibleAs(Class<E> dataClass) {
        if (type.dataClass == dataClass) {
            return (Record<E>) this;
        }
        return null;
    }

    /**
     * Return the record as record with the given {@link Data} class. If the record does not hold payload of
     * the given data class type, then a {@link IllegalArgumentException} will be thrown.
     *
     * @param dataClass a class of the {@link Data} type.
     * @param <E> a subtype of {@link Data}.
     * @return the record with a specialized payload type.
     * @see #ifPossibleAs(Class)
     */
    public <E extends Data> Record<E> as(Class<E> dataClass) {
        Record<E> eRecord = ifPossibleAs(dataClass);
        if (eRecord == null) {
            throw new IllegalArgumentException("The instance " + this + " can not be cast to a Record with" + dataClass);
        }
        return eRecord;
    }

    public static <E extends Data> void filter(Collection<Record<E>> result, Class<E> dataClass,
            Collection<Record<? extends Data>> input) {
        for (Record<? extends Data> record : input) {
            Record<E> filteredRecord = record.ifPossibleAs(dataClass);
            if (filteredRecord == null)
                continue;

            result.add(filteredRecord);
        }
    }

    public static <E extends Data> List<Record<E>> filter(Class<E> dataClass,
            Collection<Record<? extends Data>> input) {
        List<Record<E>> result = new ArrayList<>(input.size());
        filter(result, dataClass, input);
        return result;
    }
}
