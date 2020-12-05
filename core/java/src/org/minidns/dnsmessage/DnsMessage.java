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
package org.minidns.dnsmessage;

import org.minidns.edns.Edns;
import org.minidns.record.Data;
import org.minidns.record.OPT;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A DNS message as defined by RFC 1035. The message consists of a header and
 * 4 sections: question, answer, nameserver and addition resource record
 * section.
 * A message can either be parsed ({@link #DnsMessage(byte[])}) or serialized
 * ({@link DnsMessage#toArray()}).
 * 
 * @see <a href="https://www.ietf.org/rfc/rfc1035.txt">RFC 1035</a>
 */
public class DnsMessage {

    private static final Logger LOGGER = Logger.getLogger(DnsMessage.class.getName());

    /**
     * Possible DNS response codes.
     * 
     * @see <a href=
     *      "http://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-6">
     *      IANA Domain Name System (DNS) Paramters - DNS RCODEs</a>
     * @see <a href="http://tools.ietf.org/html/rfc6895#section-2.3">RFC 6895 § 2.3</a>
     */
    public enum RESPONSE_CODE {
        NO_ERROR(0),
        FORMAT_ERR(1),
        SERVER_FAIL(2),
        NX_DOMAIN(3),
        NO_IMP(4),
        REFUSED(5),
        YXDOMAIN(6),
        YXRRSET(7),
        NXRRSET(8),
        NOT_AUTH(9),
        NOT_ZONE(10),
        BADVERS_BADSIG(16),
        BADKEY(17),
        BADTIME(18),
        BADMODE(19),
        BADNAME(20),
        BADALG(21),
        BADTRUNC(22),
        BADCOOKIE(23),
        ;

        /**
         * Reverse lookup table for response codes.
         */
        private static final Map<Integer, RESPONSE_CODE> INVERSE_LUT = new HashMap<>(RESPONSE_CODE.values().length);

        static {
            for (RESPONSE_CODE responseCode : RESPONSE_CODE.values()) {
                INVERSE_LUT.put((int) responseCode.value, responseCode);
            }
        }

        /**
         * The response code value.
         */
        private final byte value;

        /**
         * Create a new response code.
         *
         * @param value The response code value.
         */
        RESPONSE_CODE(int value) {
            this.value = (byte) value;
        }

        /**
         * Retrieve the byte value of the response code.
         *
         * @return the response code.
         */
        public byte getValue() {
            return value;
        }

        /**
         * Retrieve the response code for a byte value.
         *
         * @param value The byte value.
         * @return The symbolic response code or null.
         * @throws IllegalArgumentException if the value is not in the range of 0..15.
         */
        public static RESPONSE_CODE getResponseCode(int value) throws IllegalArgumentException {
            if (value < 0 || value > 65535) {
                throw new IllegalArgumentException();
            }
            return INVERSE_LUT.get(value);
        }

    }

    /**
     * Symbolic DNS Opcode values.
     * 
     * @see <a href=
     *      "http://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-5">
     *      IANA Domain Name System (DNS) Paramters - DNS OpCodes</a>
     */
    public enum OPCODE {
        QUERY,
        INVERSE_QUERY,
        STATUS,
        UNASSIGNED3,
        NOTIFY,
        UPDATE,
        ;

        /**
         * Lookup table for for opcode resolution.
         */
        private static final OPCODE[] INVERSE_LUT = new OPCODE[OPCODE.values().length];

        static {
            for (OPCODE opcode : OPCODE.values()) {
                if (INVERSE_LUT[opcode.getValue()] != null) {
                    throw new IllegalStateException();
                }
                INVERSE_LUT[opcode.getValue()] = opcode;
            }
        }

        /**
         * The value of this opcode.
         */
        private final byte value;

        /**
         * Create a new opcode for a given byte value.
         *
         */
        OPCODE() {
            this.value = (byte) this.ordinal();
        }

        /**
         * Retrieve the byte value of this opcode.
         *
         * @return The byte value of this opcode.
         */
        public byte getValue() {
            return value;
        }

        /**
         * Retrieve the symbolic name of an opcode byte.
         *
         * @param value The byte value of the opcode.
         * @return The symbolic opcode or null.
         * @throws IllegalArgumentException If the byte value is not in the
         *                                  range 0..15.
         */
        public static OPCODE getOpcode(int value) throws IllegalArgumentException {
            if (value < 0 || value > 15) {
                throw new IllegalArgumentException();
            }
            if (value >= INVERSE_LUT.length) {
                return null;
            }
            return INVERSE_LUT[value];
        }

    }

    /**
     * The DNS message id.
     */
    public final int id;

    /**
     * The DNS message opcode.
     */
    public final OPCODE opcode;

    /**
     * The response code of this dns message.
     */
    public final RESPONSE_CODE responseCode;

    /**
     * The QR flag of the DNS message header. Note that this will be <code>true</code> if the message is a
     * <b>response</b> and <code>false</code> if it is a <b>query</b>.
     * 
     * @see <a href="https://www.ietf.org/rfc/rfc1035.txt">RFC 1035 § 4.1.1</a>
     */
    public final boolean qr;

    /**
     * True if this is a authorative response. If set, the responding nameserver is an authority for the domain name in
     * the question section. Note that the answer section may have multiple owner names because of aliases. This flag
     * corresponds to the name which matches the query name, or the first owner name in the query section.
     *
     * @see <a href="https://www.ietf.org/rfc/rfc1035.txt">RFC 1035 § 4.1.1. Header section format</a>
     */
    public final boolean authoritativeAnswer;

    /**
     * True if message is truncated. Then TCP should be used.
     */
    public final boolean truncated;

    /**
     * True if the server should recurse.
     */
    public final boolean recursionDesired;

    /**
     * True if recursion is possible.
     */
    public final boolean recursionAvailable;

    /**
     * True if the server regarded the response as authentic.
     */
    public final boolean authenticData;

    /**
     * True if the server should not perform DNSSEC validation before returning the result.
     */
    public final boolean checkingDisabled;

    /**
     * The question section content. Usually there will be only one question.
     * <p>
     * This list is unmodifiable.
     * </p>
     */
    public final List<Question> questions;

    /**
     * The answers section records. Note that it is not guaranteed that all records found in this section will be direct
     * answers to the question in the query. If DNSSEC is used, then this section also contains the RRSIG record.
     * <p>
     * This list is unmodifiable.
     * </p>
     */
    public final List<Record<? extends Data>> answerSection;

    /**
     * The Authority Section. Note that it is not guaranteed that this section only contains nameserver records. If DNSSEC is used, then this section could also contain a NSEC(3) record.
     * <p>
     * This list is unmodifiable.
     * </p>
     */
    public final List<Record<? extends Data>> authoritySection;

    /**
     * The additional section. It eventually contains RRs which relate to the query.
     * <p>
     * This list is unmodifiable.
     * </p> 
     */
    public final List<Record<? extends Data>> additionalSection;

    public final int optRrPosition;

    /**
     * The optional but very common EDNS information. Note that this field is lazily populated.
     *
     */
    private Edns edns;

    /**
     * The receive timestamp. Set only if this message was created via parse.
     * This should be used to evaluate TTLs.
     */
    public final long receiveTimestamp;

    protected DnsMessage(Builder builder) {
        this.id = builder.id;
        this.opcode = builder.opcode;
        this.responseCode = builder.responseCode;
        this.receiveTimestamp = builder.receiveTimestamp;
        this.qr = builder.query;
        this.authoritativeAnswer = builder.authoritativeAnswer;
        this.truncated = builder.truncated;
        this.recursionDesired = builder.recursionDesired;
        this.recursionAvailable = builder.recursionAvailable;
        this.authenticData = builder.authenticData;
        this.checkingDisabled = builder.checkingDisabled;

        if (builder.questions == null) {
            this.questions = Collections.emptyList();
        } else {
            List<Question> q = new ArrayList<>(builder.questions.size());
            q.addAll(builder.questions);
            this.questions = Collections.unmodifiableList(q);
        }

        if (builder.answerSection == null) {
            this.answerSection = Collections.emptyList();
        } else {
            List<Record<? extends Data>> a = new ArrayList<>(builder.answerSection.size());
            a.addAll(builder.answerSection);
            this.answerSection = Collections.unmodifiableList(a);
        }

        if (builder.authoritySection == null) {
            this.authoritySection = Collections.emptyList();
        } else {
            List<Record<? extends Data>> n = new ArrayList<>(builder.authoritySection.size());
            n.addAll(builder.authoritySection);
            this.authoritySection = Collections.unmodifiableList(n);
        }

        if (builder.additionalSection == null && builder.ednsBuilder == null) {
            this.additionalSection = Collections.emptyList();
        } else {
            int size = 0;
            if (builder.additionalSection != null) {
                size += builder.additionalSection.size();
            }
            if (builder.ednsBuilder != null) {
                size++;
            }
            List<Record<? extends Data>> a = new ArrayList<>(size);
            if (builder.additionalSection != null) {
                a.addAll(builder.additionalSection);
            }
            if (builder.ednsBuilder != null) {
                Edns edns = builder.ednsBuilder.build();
                this.edns = edns;
                a.add(edns.asRecord());
            }
            this.additionalSection = Collections.unmodifiableList(a);
        }

        optRrPosition = getOptRrPosition(this.additionalSection);

        if (optRrPosition != -1) {
            // Verify that there are no further OPT records but the one we already found.
            for (int i = optRrPosition + 1; i < this.additionalSection.size(); i++) {
                if (this.additionalSection.get(i).type == TYPE.OPT) {
                    throw new IllegalArgumentException("There must be only one OPT pseudo RR in the additional section");
                }
            }
        }

        // TODO Add verification of dns message state here
    }

    /**
     * Build a DNS Message based on a binary DNS message.
     *
     * @param data The DNS message data.
     * @throws IOException On read errors.
     */
    public DnsMessage(byte[] data) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bis);
        id = dis.readUnsignedShort();
        int header = dis.readUnsignedShort();
        qr = ((header >> 15) & 1) == 1;
        opcode = OPCODE.getOpcode((header >> 11) & 0xf);
        authoritativeAnswer = ((header >> 10) & 1) == 1;
        truncated = ((header >> 9) & 1) == 1;
        recursionDesired = ((header >> 8) & 1) == 1;
        recursionAvailable = ((header >> 7) & 1) == 1;
        authenticData = ((header >> 5) & 1) == 1;
        checkingDisabled = ((header >> 4) & 1) == 1;
        responseCode = RESPONSE_CODE.getResponseCode(header & 0xf);
        receiveTimestamp = System.currentTimeMillis();
        int questionCount = dis.readUnsignedShort();
        int answerCount = dis.readUnsignedShort();
        int nameserverCount = dis.readUnsignedShort();
        int additionalResourceRecordCount = dis.readUnsignedShort();
        questions = new ArrayList<>(questionCount);
        for (int i = 0; i < questionCount; i++) {
            questions.add(new Question(dis, data));
        }
        answerSection = new ArrayList<>(answerCount);
        for (int i = 0; i < answerCount; i++) {
            answerSection.add(Record.parse(dis, data));
        }
        authoritySection = new ArrayList<>(nameserverCount);
        for (int i = 0; i < nameserverCount; i++) {
            authoritySection.add(Record.parse(dis, data));
        }
        additionalSection = new ArrayList<>(additionalResourceRecordCount);
        for (int i = 0; i < additionalResourceRecordCount; i++) {
            additionalSection.add(Record.parse(dis, data));
        }
        optRrPosition = getOptRrPosition(additionalSection);
    }

    /**
     * Constructs an normalized version of the given DnsMessage by setting the id to '0'.
     *
     * @param message the message of which normalized version should be constructed.
     */
    private DnsMessage(DnsMessage message) {
        id = 0;
        qr = message.qr;
        opcode = message.opcode;
        authoritativeAnswer = message.authoritativeAnswer;
        truncated = message.truncated;
        recursionDesired = message.recursionDesired;
        recursionAvailable = message.recursionAvailable;
        authenticData = message.authenticData;
        checkingDisabled = message.checkingDisabled;
        responseCode = message.responseCode;
        receiveTimestamp = message.receiveTimestamp;
        questions = message.questions;
        answerSection = message.answerSection;
        authoritySection = message.authoritySection;
        additionalSection = message.additionalSection;
        optRrPosition = message.optRrPosition;
    }

    private static int getOptRrPosition(List<Record<? extends Data>> additionalSection) {
        int optRrPosition = -1;
        for (int i = 0; i < additionalSection.size(); i++) {
            Record<? extends Data> record = additionalSection.get(i);
            if (record.type == Record.TYPE.OPT) {
                optRrPosition = i;
                break;
            }
        }
        return optRrPosition;
    }

    /**
     * Generate a binary dns packet out of this message.
     *
     * @return byte[] the binary representation.
     */
    public byte[] toArray() {
        return serialize().clone();
    }

    public DatagramPacket asDatagram(InetAddress address, int port) {
        byte[] bytes = serialize();
        return new DatagramPacket(bytes, bytes.length, address, port);
    }

    public void writeTo(OutputStream outputStream) throws IOException {
        writeTo(outputStream, true);
    }

    public void writeTo(OutputStream outputStream, boolean writeLength) throws IOException {
        byte[] bytes = serialize();
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        if (writeLength) {
            dataOutputStream.writeShort(bytes.length);
        }
        dataOutputStream.write(bytes);
    }

    public ByteBuffer getInByteBuffer() {
        byte[] bytes = serialize().clone();
        return ByteBuffer.wrap(bytes);
    }

    private byte[] byteCache;

    private byte[] serialize() {
        if (byteCache != null) {
            return byteCache;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        DataOutputStream dos = new DataOutputStream(baos);
        int header = calculateHeaderBitmap();
        try {
            dos.writeShort((short) id);
            dos.writeShort((short) header);
            if (questions == null) {
                dos.writeShort(0);
            } else {
                dos.writeShort((short) questions.size());
            }
            if (answerSection == null) {
                dos.writeShort(0);
            } else {
                dos.writeShort((short) answerSection.size());
            }
            if (authoritySection == null) {
                dos.writeShort(0);
            } else {
                dos.writeShort((short) authoritySection.size());
            }
            if (additionalSection == null) {
                dos.writeShort(0);
            } else {
                dos.writeShort((short) additionalSection.size());
            }
            if (questions != null) {
                for (Question question : questions) {
                    dos.write(question.toByteArray());
                }
            }
            if (answerSection != null) {
                for (Record<? extends Data> answer : answerSection) {
                    dos.write(answer.toByteArray());
                }
            }
            if (authoritySection != null) {
                for (Record<? extends Data> nameserverRecord : authoritySection) {
                    dos.write(nameserverRecord.toByteArray());
                }
            }
            if (additionalSection != null) {
                for (Record<? extends Data> additionalResourceRecord : additionalSection) {
                    dos.write(additionalResourceRecord.toByteArray());
                }
            }
            dos.flush();
        } catch (IOException e) {
            // Should never happen.
            throw new AssertionError(e);
        }
        byteCache = baos.toByteArray();
        return byteCache;
    }

    int calculateHeaderBitmap() {
        int header = 0;
        if (qr) {
            header += 1 << 15;
        }
        if (opcode != null) {
            header += opcode.getValue() << 11;
        }
        if (authoritativeAnswer) {
            header += 1 << 10;
        }
        if (truncated) {
            header += 1 << 9;
        }
        if (recursionDesired) {
            header += 1 << 8;
        }
        if (recursionAvailable) {
            header += 1 << 7;
        }
        if (authenticData) {
            header += 1 << 5;
        }
        if (checkingDisabled) {
            header += 1 << 4;
        }
        if (responseCode != null) {
            header += responseCode.getValue();
        }
        return header;
    }

    public Question getQuestion() {
        return questions.get(0);
    }

    /**
     * Copy the questions found in the question section.
     *
     * @return a copy of the question section questions.
     * @see #questions
     */
    public List<Question> copyQuestions() {
        List<Question> copy = new ArrayList<>(questions.size());
        copy.addAll(questions);
        return copy;
    }

    /**
     * Copy the records found in the answer section into a new list.
     *
     * @return a copy of the answer section records.
     * @see #answerSection
     */
    public List<Record<? extends Data>> copyAnswers() {
        List<Record<? extends Data>> res = new ArrayList<>(answerSection.size());
        res.addAll(answerSection);
        return res;
    }

    /**
     * Copy the records found in the authority section into a new list.
     *
     * @return a copy of the authority section records.
     * @see #authoritySection
     */
    public List<Record<? extends Data>> copyAuthority() {
        List<Record<? extends Data>> res = new ArrayList<>(authoritySection.size());
        res.addAll(authoritySection);
        return res;
    }

    public Edns getEdns() {
        if (edns != null) return edns;

        Record<OPT> optRecord = getOptPseudoRecord();
        if (optRecord == null) return null;
        edns = new Edns(optRecord);
        return edns;
    }

    @SuppressWarnings("unchecked")
    public Record<OPT> getOptPseudoRecord() {
        if (optRrPosition == -1) return null;
        return (Record<OPT>) additionalSection.get(optRrPosition);
    }

    /**
     * Check if the EDNS DO (DNSSEC OK) flag is set.
     *
     * @return true if the DO flag is set.
     */
    public boolean isDnssecOk() {
        Edns edns = getEdns();
        if (edns == null)
            return false;

        return edns.dnssecOk;
    }

    private String toStringCache;

    @Override
    public String toString() {
        if (toStringCache != null) return toStringCache;

        StringBuilder sb = new StringBuilder("DnsMessage");
        asBuilder().writeToStringBuilder(sb);

        toStringCache = sb.toString();
        return toStringCache;
    }

    private String terminalOutputCache;

    /**
     * Format the DnsMessage object in a way suitable for terminal output.
     * The format is loosely based on the output provided by {@code dig}.
     *
     * @return This message as a String suitable for terminal output.
     */
    public String asTerminalOutput() {
        if (terminalOutputCache != null) return terminalOutputCache;

        StringBuilder sb = new StringBuilder(";; ->>HEADER<<-")
                .append(" opcode: ").append(opcode)
                .append(", status: ").append(responseCode)
                .append(", id: ").append(id).append("\n")
                .append(";; flags:");
        if (!qr) sb.append(" qr");
        if (authoritativeAnswer) sb.append(" aa");
        if (truncated) sb.append(" tr");
        if (recursionDesired) sb.append(" rd");
        if (recursionAvailable) sb.append(" ra");
        if (authenticData) sb.append(" ad");
        if (checkingDisabled) sb.append(" cd");
        sb.append("; QUERY: ").append(questions.size())
                .append(", ANSWER: ").append(answerSection.size())
                .append(", AUTHORITY: ").append(authoritySection.size())
                .append(", ADDITIONAL: ").append(additionalSection.size())
                .append("\n\n");
        for (Record<? extends Data> record : additionalSection) {
            Edns edns = Edns.fromRecord(record);
            if (edns != null) {
                sb.append(";; OPT PSEUDOSECTION:\n; ").append(edns.asTerminalOutput());
                break;
            }
        }
        if (questions.size() != 0) {
            sb.append(";; QUESTION SECTION:\n");
            for (Question question : questions) {
                sb.append(';').append(question.toString()).append('\n');
            }
        }
        if (authoritySection.size() != 0) {
            sb.append("\n;; AUTHORITY SECTION:\n");
            for (Record<? extends Data> record : authoritySection) {
                sb.append(record.toString()).append('\n');
            }
        }
        if (answerSection.size() != 0) {
            sb.append("\n;; ANSWER SECTION:\n");
            for (Record<? extends Data> record : answerSection) {
                sb.append(record.toString()).append('\n');
            }
        }
        if (additionalSection.size() != 0) {
            boolean hasNonOptArr = false;
            for (Record<? extends Data> record : additionalSection) {
                if (record.type != Record.TYPE.OPT) {
                    if (!hasNonOptArr) {
                        hasNonOptArr = true;
                        sb.append("\n;; ADDITIONAL SECTION:\n");
                    }
                    sb.append(record.toString()).append('\n');
                }
            }
        }
        if (receiveTimestamp > 0) {
            sb.append("\n;; WHEN: ").append(new Date(receiveTimestamp).toString());
        }
        terminalOutputCache = sb.toString();
        return terminalOutputCache;
    }

    public <D extends Data> Set<D> getAnswersFor(Question q) {
        if (responseCode != RESPONSE_CODE.NO_ERROR) return null;

        // It would be great if we could verify that D matches q.type at this
        // point. But on the other hand, if it does not, then the cast to D
        // below will fail.
        Set<D> res = new HashSet<>(answerSection.size());
        for (Record<? extends Data> record : answerSection) {
            if (!record.isAnswer(q)) continue;

            Data data = record.getPayload();
            @SuppressWarnings("unchecked")
            D d = (D) data;
            boolean isNew = res.add(d);
            if (!isNew) {
                LOGGER.log(Level.WARNING, "DnsMessage contains duplicate answers. Record: " + record + "; DnsMessage: " + this);
            }
        }
        return res;
    }

    private long answersMinTtlCache = -1;

    /**
     * Get the minimum TTL from all answers in seconds.
     *
     * @return the minimum TTL from all answers in seconds.
     */
    public long getAnswersMinTtl() {
        if (answersMinTtlCache >= 0) {
            return answersMinTtlCache;
        }

        answersMinTtlCache = Long.MAX_VALUE;
        for (Record<? extends Data> r : answerSection) {
            answersMinTtlCache = Math.min(answersMinTtlCache, r.ttl);
        }
        return answersMinTtlCache;
    }

    public Builder asBuilder() {
        return new Builder(this);
    }

    private DnsMessage normalizedVersionCache;

    public DnsMessage asNormalizedVersion() {
        if (normalizedVersionCache == null) {
            normalizedVersionCache = new DnsMessage(this);
        }
        return normalizedVersionCache;
    }

    public Builder getResponseBuilder(RESPONSE_CODE responseCode) {
        if (qr) {
            throw new IllegalStateException();
        }
        Builder responseBuilder = DnsMessage.builder()
                .setQrFlag(true)
                .setResponseCode(responseCode)
                .setId(id)
                .setQuestion(getQuestion());

        return responseBuilder;
    }

    private transient Integer hashCodeCache;

    @Override
    public int hashCode() {
        if (hashCodeCache == null) {
            byte[] bytes = serialize();
            hashCodeCache = Arrays.hashCode(bytes);
        }
        return hashCodeCache;
    }

    private enum SectionName {
        answer,
        authority,
        additional,
    }

    private <D extends Data> List<Record<D>> filterSectionByType(boolean stopOnFirst, SectionName sectionName, Class<D> type) {
        List<Record<?>> sectionToFilter;
        switch (sectionName) {
        case answer:
            sectionToFilter = answerSection;
            break;
        case authority:
            sectionToFilter = authoritySection;
            break;
        case additional:
            sectionToFilter = additionalSection;
            break;
        default:
            throw new AssertionError("Unknown section name " + sectionName);
        }

        List<Record<D>> res = new ArrayList<>(stopOnFirst ? 1 : sectionToFilter.size());

        for (Record<?> record : sectionToFilter) {
            Record<D> target = record.ifPossibleAs(type);
            if (target != null) {
                res.add(target);
                if (stopOnFirst) {
                    return res;
                }
            }
        }

        return res;
    }

    private <D extends Data> List<Record<D>> filterSectionByType(SectionName sectionName, Class<D> type) {
        return filterSectionByType(false, sectionName, type);
    }

    private <D extends Data> Record<D> getFirstOfType(SectionName sectionName, Class<D> type) {
        List<Record<D>> result = filterSectionByType(true, sectionName, type);
        if (result.isEmpty()) {
            return null;
        }

        return result.get(0);
    }

    public <D extends Data> List<Record<D>> filterAnswerSectionBy(Class<D> type) {
        return filterSectionByType(SectionName.answer, type);
    }

    public <D extends Data> List<Record<D>> filterAuthoritySectionBy(Class<D> type) {
        return filterSectionByType(SectionName.authority, type);
    }

    public <D extends Data> List<Record<D>> filterAdditionalSectionBy(Class<D> type) {
        return filterSectionByType(SectionName.additional, type);
    }

    public <D extends Data> Record<D> getFirstOfTypeFromAnswerSection(Class<D> type) {
        return getFirstOfType(SectionName.answer, type);
    }

    public <D extends Data> Record<D> getFirstOfTypeFromAuthoritySection(Class<D> type) {
        return getFirstOfType(SectionName.authority, type);
    }

    public <D extends Data> Record<D> getFirstOfTypeFromAdditionalSection(Class<D> type) {
        return getFirstOfType(SectionName.additional, type);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof DnsMessage)) {
            return false;
        }
        if (other == this) {
            return true;
        }
        DnsMessage otherDnsMessage = (DnsMessage) other;
        byte[] otherBytes = otherDnsMessage.serialize();
        byte[] myBytes = serialize();
        return Arrays.equals(myBytes, otherBytes);
    }

    public static Builder builder() {
        return new DnsMessage.Builder();
    }

    public static final class Builder {

        private Builder() {
        }

        private Builder(DnsMessage message) {
            id = message.id;
            opcode = message.opcode;
            responseCode = message.responseCode;
            query = message.qr;
            authoritativeAnswer = message.authoritativeAnswer;
            truncated = message.truncated;
            recursionDesired = message.recursionDesired;
            recursionAvailable = message.recursionAvailable;
            authenticData = message.authenticData;
            checkingDisabled = message.checkingDisabled;
            receiveTimestamp = message.receiveTimestamp;

            // Copy the unmodifiable lists over into this new builder.
            questions = new ArrayList<>(message.questions.size());
            questions.addAll(message.questions);
            answerSection = new ArrayList<>(message.answerSection.size());
            answerSection.addAll(message.answerSection);
            authoritySection = new ArrayList<>(message.authoritySection.size());
            authoritySection.addAll(message.authoritySection);
            additionalSection = new ArrayList<>(message.additionalSection.size());
            additionalSection.addAll(message.additionalSection);
        }

        private int id;
        private OPCODE opcode = OPCODE.QUERY;
        private RESPONSE_CODE responseCode = RESPONSE_CODE.NO_ERROR;
        private boolean query;
        private boolean authoritativeAnswer;
        private boolean truncated;
        private boolean recursionDesired;
        private boolean recursionAvailable;
        private boolean authenticData;
        private boolean checkingDisabled;

        private long receiveTimestamp = -1;

        private List<Question> questions;
        private List<Record<? extends Data>> answerSection;
        private List<Record<? extends Data>> authoritySection;
        private List<Record<? extends Data>> additionalSection;
        private Edns.Builder ednsBuilder;

        /**
         * Set the current DNS message id.
         *
         * @param id The new DNS message id.
         * @return a reference to this builder.
         */
        public Builder setId(int id) {
            this.id = id & 0xffff;
            return this;
        }

        public Builder setOpcode(OPCODE opcode) {
            this.opcode = opcode;
            return this;
        }

        public Builder setResponseCode(RESPONSE_CODE responseCode) {
            this.responseCode = responseCode;
            return this;
        }

        /**
         * Set the QR flag. Note that this will be <code>true</code> if the message is a
         * <b>response</b> and <code>false</code> if it is a <b>query</b>.
         *
         * @param query The new QR flag status.
         * @return a reference to this builder.
         */
        public Builder setQrFlag(boolean query) {
            this.query = query;
            return this;
        }

        /**
         * Set the authoritative answer flag.
         *
         * @param authoritativeAnswer Tge new authoritative answer value.
         * @return a reference to this builder.
         */
        public Builder setAuthoritativeAnswer(boolean authoritativeAnswer) {
            this.authoritativeAnswer = authoritativeAnswer;
            return this;
        }

        /**
         * Set the truncation bit on this DNS message.
         *
         * @param truncated The new truncated bit status.
         * @return a reference to this builder.
         */
        public Builder setTruncated(boolean truncated) {
            this.truncated = truncated;
            return this;
        }

        /**
         * Set the recursion desired flag on this message.
         *
         * @param recursionDesired The new recusrion setting.
         * @return a reference to this builder.
         */
        public Builder setRecursionDesired(boolean recursionDesired) {
            this.recursionDesired = recursionDesired;
            return this;
        }

        /**
         * Set the recursion available flog from this DNS message.
         *
		 * @param recursionAvailable The new recursion available status.
         * @return a reference to this builder.
         */
        public Builder setRecursionAvailable(boolean recursionAvailable) {
            this.recursionAvailable = recursionAvailable;
            return this;
        }

        /**
         * Set the authentic data flag on this DNS message.
         *
         * @param authenticData The new authentic data flag value.
         * @return a reference to this builder.
         */
        public Builder setAuthenticData(boolean authenticData) {
            this.authenticData = authenticData;
            return this;
        }

        /**
         * Change the check status of this packet.
         *
         * @param checkingDisabled The new check disabled value.
         * @return a reference to this builder.
         */
        @Deprecated
        public Builder setCheckDisabled(boolean checkingDisabled) {
            this.checkingDisabled = checkingDisabled;
            return this;
        }

        /**
         * Change the check status of this packet.
         *
         * @param checkingDisabled The new check disabled value.
         * @return a reference to this builder.
         */
        public Builder setCheckingDisabled(boolean checkingDisabled) {
            this.checkingDisabled = checkingDisabled;
            return this;
        }

        public void copyFlagsFrom(DnsMessage dnsMessage) {
            this.query = dnsMessage.qr;
            this.authoritativeAnswer = dnsMessage.authenticData;
            this.truncated = dnsMessage.truncated;
            this.recursionDesired = dnsMessage.recursionDesired;
            this.recursionAvailable = dnsMessage.recursionAvailable;
            this.authenticData = dnsMessage.authenticData;
            this.checkingDisabled = dnsMessage.checkingDisabled;
        }

        public Builder setReceiveTimestamp(long receiveTimestamp) {
            this.receiveTimestamp = receiveTimestamp;
            return this;
        }

        public Builder addQuestion(Question question) {
            if (questions == null) {
                questions = new ArrayList<>(1);
            }
            questions.add(question);
            return this;
        }

        /**
         * Set the question part of this message.
         *
         * @param questions The questions.
         * @return a reference to this builder.
         */
        public Builder setQuestions(List<Question> questions) {
            this.questions = questions;
            return this;
        }

        /**
         * Set the question part of this message.
         *
         * @param question The question.
         * @return a reference to this builder.
         */
        public Builder setQuestion(Question question) {
            this.questions = new ArrayList<>(1);
            this.questions.add(question);
            return this;
        }

        public Builder addAnswer(Record<? extends Data> answer) {
            if (answerSection == null) {
                answerSection = new ArrayList<>(1);
            }
            answerSection.add(answer);
            return this;
        }

        public Builder addAnswers(Collection<Record<? extends Data>> records) {
            if (answerSection == null) {
                answerSection = new ArrayList<>(records.size());
            }
            answerSection.addAll(records);
            return this;
        }

        public Builder setAnswers(Collection<Record<? extends Data>> records) {
            answerSection = new ArrayList<>(records.size());
            answerSection.addAll(records);
            return this;
        }

        public List<Record<? extends Data>> getAnswers() {
            if (answerSection == null) {
                return Collections.emptyList();
            }
            return answerSection;
        }

        public Builder addNameserverRecords(Record<? extends Data> record) {
            if (authoritySection == null) {
                authoritySection = new ArrayList<>(8);
            }
            authoritySection.add(record);
            return this;
        }

        public Builder setNameserverRecords(Collection<Record<? extends Data>> records) {
            authoritySection = new ArrayList<>(records.size());
            authoritySection.addAll(records);
            return this;
        }

        public Builder setAdditionalResourceRecords(Collection<Record<? extends Data>> records) {
            additionalSection = new ArrayList<>(records.size());
            additionalSection.addAll(records);
            return this;
        }

        public Builder addAdditionalResourceRecord(Record<? extends Data> record) {
            if (additionalSection == null) {
                additionalSection = new ArrayList<>();
            }
            additionalSection.add(record);
            return this;
        }

        public Builder addAdditionalResourceRecords(List<Record<? extends Data>> records) {
            if (additionalSection == null) {
                additionalSection = new ArrayList<>(records.size());
            }
            additionalSection.addAll(records);
            return this;
        }

        public List<Record<? extends Data>> getAdditionalResourceRecords() {
            if (additionalSection == null) {
                return Collections.emptyList();
            }
            return additionalSection;
        }

        /**
         * Get the @{link EDNS} builder. If no builder has been set so far, then a new one will be created.
         * <p>
         * The EDNS record can be used to announce the supported size of UDP payload as well as additional flags.
         * </p>
         * <p>
         * Note that some networks and firewalls are known to block big UDP payloads. 1280 should be a reasonable value,
         * everything below 512 is treated as 512 and should work on all networks.
         * </p>
         * 
         * @return a EDNS builder.
         */
        public Edns.Builder getEdnsBuilder() {
            if (ednsBuilder == null) {
                ednsBuilder = Edns.builder();
            }
            return ednsBuilder;
        }

        public DnsMessage build() {
            return new DnsMessage(this);
        }

        private void writeToStringBuilder(StringBuilder sb) {
            sb.append('(')
                .append(id)
                .append(' ')
                .append(opcode)
                .append(' ')
                .append(responseCode)
                .append(' ');
            if (query) {
                sb.append("resp[qr=1]");
            } else {
                sb.append("query[qr=0]");
            }
            if (authoritativeAnswer)
                sb.append(" aa");
            if (truncated)
                sb.append(" tr");
            if (recursionDesired)
                sb.append(" rd");
            if (recursionAvailable)
                sb.append(" ra");
            if (authenticData)
                sb.append(" ad");
            if (checkingDisabled)
                sb.append(" cd");
            sb.append(")\n");
            if (questions != null) {
                for (Question question : questions) {
                    sb.append("[Q: ").append(question).append("]\n");
                }
            }
            if (answerSection != null) {
                for (Record<? extends Data> record : answerSection) {
                    sb.append("[A: ").append(record).append("]\n");
                }
            }
            if (authoritySection != null) {
                for (Record<? extends Data> record : authoritySection) {
                    sb.append("[N: ").append(record).append("]\n");
                }
            }
            if (additionalSection != null) {
                for (Record<? extends Data> record : additionalSection) {
                    sb.append("[X: ");
                    Edns edns = Edns.fromRecord(record);
                    if (edns != null) {
                        sb.append(edns.toString());
                    } else {
                        sb.append(record);
                    }
                    sb.append("]\n");
                }
            }

            // Strip trailing newline.
            if (sb.charAt(sb.length() - 1) == '\n') {
                sb.setLength(sb.length() - 1);
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Builder of DnsMessage");
            writeToStringBuilder(sb);
            return sb.toString();
        }
    }

}
