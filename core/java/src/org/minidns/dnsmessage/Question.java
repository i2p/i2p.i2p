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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.CLASS;
import org.minidns.record.Record.TYPE;

/**
 * A DNS question (request).
 */
public class Question {

    /**
     * The question string (e.g. "measite.de").
     */
    public final DnsName name;

    /**
     * The question type (e.g. A).
     */
    public final TYPE type;

    /**
     * The question class (usually IN for Internet).
     */
    public final CLASS clazz;

    /**
     * UnicastQueries have the highest bit of the CLASS field set to 1.
     */
    private final boolean unicastQuery;

    /**
     * Cache for the serialized object.
     */
    private byte[] byteArray;

    /**
     * Create a dns question for the given name/type/class.
     * @param name The name e.g. "measite.de".
     * @param type The type, e.g. A.
     * @param clazz The class, usually IN (internet).
     * @param unicastQuery True if this is a unicast query.
     */
    public Question(CharSequence name, TYPE type, CLASS clazz, boolean unicastQuery) {
        this(DnsName.from(name), type, clazz, unicastQuery);
    }

    public Question(DnsName name, TYPE type, CLASS clazz, boolean unicastQuery) {
        assert name != null;
        assert type != null;
        assert clazz != null;
        this.name = name;
        this.type = type;
        this.clazz = clazz;
        this.unicastQuery = unicastQuery;
    }

    /**
     * Create a dns question for the given name/type/class.
     * @param name The name e.g. "measite.de".
     * @param type The type, e.g. A.
     * @param clazz The class, usually IN (internet).
     */
    public Question(DnsName name, TYPE type, CLASS clazz) {
        this(name, type, clazz, false);
    }

    /**
     * Create a dns question for the given name/type/IN (internet class).
     * @param name The name e.g. "measite.de".
     * @param type The type, e.g. A.
     */
    public Question(DnsName name, TYPE type) {
        this(name, type, CLASS.IN);
    }

    /**
     * Create a dns question for the given name/type/class.
     * @param name The name e.g. "measite.de".
     * @param type The type, e.g. A.
     * @param clazz The class, usually IN (internet).
     */
    public Question(CharSequence name, TYPE type, CLASS clazz) {
        this(DnsName.from(name), type, clazz);
    }

    /**
     * Create a dns question for the given name/type/IN (internet class).
     * @param name The name e.g. "measite.de".
     * @param type The type, e.g. A.
     */
    public Question(CharSequence name, TYPE type) {
        this(DnsName.from(name), type);
    }

    /**
     * Parse a byte array and rebuild the dns question from it.
     * @param dis The input stream.
     * @param data The plain data (for dns name references).
     * @throws IOException On errors (read outside of packet).
     */
    public Question(DataInputStream dis, byte[] data) throws IOException {
        name = DnsName.parse(dis, data);
        type = TYPE.getType(dis.readUnsignedShort());
        clazz = CLASS.getClass(dis.readUnsignedShort());
        unicastQuery = false;
    }

    /**
     * Generate a binary paket for this dns question.
     * @return The dns question.
     */
    public byte[] toByteArray() {
        if (byteArray == null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            DataOutputStream dos = new DataOutputStream(baos);

            try {
                name.writeToStream(dos);
                dos.writeShort(type.getValue());
                dos.writeShort(clazz.getValue() | (unicastQuery ? (1 << 15) : 0));
                dos.flush();
            } catch (IOException e) {
                // Should never happen
                throw new RuntimeException(e);
            }
            byteArray = baos.toByteArray();
        }
        return byteArray;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toByteArray());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Question)) {
            return false;
        }
        byte[] t = toByteArray();
        byte[] o = ((Question) other).toByteArray();
        return Arrays.equals(t, o);
    }

    @Override
    public String toString() {
        return name.getRawAce() + ".\t" + clazz + '\t' + type;
    }

    public DnsMessage.Builder asMessageBuilder() {
        DnsMessage.Builder builder = DnsMessage.builder();
        builder.setQuestion(this);
        return builder;
    }

    public DnsMessage asQueryMessage() {
        return asMessageBuilder().build();
    }
}
