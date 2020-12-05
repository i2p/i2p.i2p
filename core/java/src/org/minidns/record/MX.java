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

import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.TYPE;

/**
 * MX record payload (mail service pointer).
 */
public class MX extends Data {

    /**
     * The priority of this service. Lower values mean higher priority.
     */
    public final int priority;

    /**
     * The name of the target server.
     */
    public final DnsName target;

    /**
     * The name of the target server.
     *
     * @deprecated use {@link #target} instead.
     */
    @Deprecated
    public final DnsName name;

    public static MX parse(DataInputStream dis, byte[] data)
        throws IOException {
        int priority = dis.readUnsignedShort();
        DnsName name = DnsName.parse(dis, data);
        return new MX(priority, name);
    }

    public MX(int priority, String name) {
        this(priority, DnsName.from(name));
    }

    public MX(int priority, DnsName name) {
        this.priority = priority;
        this.target = name;
        this.name = target;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeShort(priority);
        target.writeToStream(dos);
    }

    @Override
    public String toString() {
        return priority + " " + target + '.';
    }

    @Override
    public TYPE getType() {
        return TYPE.MX;
    }

}
