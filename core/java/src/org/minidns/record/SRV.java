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
 * SRV record payload (service pointer).
 */
public class SRV extends Data implements Comparable<SRV> {

    /**
     * The priority of this service. Lower values mean higher priority.
     */
    public final int priority;

    /**
     * The weight of this service. Services with the same priority should be
     * balanced based on weight.
     */
    public final int weight;

    /**
     * The target port.
     */
    public final int port;

    /**
     * The target server.
     */
    public final DnsName target;

    /**
     * The target server.
     *
     * @deprecated use {@link #target} instead.
     */
    @Deprecated
    public final DnsName name;

    public static SRV parse(DataInputStream dis, byte[] data)
        throws IOException {
        int priority = dis.readUnsignedShort();
        int weight = dis.readUnsignedShort();
        int port = dis.readUnsignedShort();
        DnsName name = DnsName.parse(dis, data);
        return new SRV(priority, weight, port, name);
    }

    public SRV(int priority, int weight, int port, String name) {
        this(priority, weight, port, DnsName.from(name));
    }

    public SRV(int priority, int weight, int port, DnsName name) {
        this.priority = priority;
        this.weight = weight;
        this.port = port;
        this.target = name;
        this.name = target;
    }

    /**
     * Check if the service is available at this domain. This checks f the target points to the root label. As per RFC
     * 2782 the service is decidedly not available if there is only a single SRV answer pointing to the root label. From
     * RFC 2782:
     *
     * <blockquote>A Target of "." means that the service is decidedly not available at this domain.</blockquote>
     *
     * @return true if the service is available at this domain.
     */
    public boolean isServiceAvailable() {
        return !target.isRootLabel();
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.writeShort(priority);
        dos.writeShort(weight);
        dos.writeShort(port);
        target.writeToStream(dos);
    }

    @Override
    public String toString() {
        return priority + " " + weight + " " + port + " " + target + ".";
    }

    @Override
    public TYPE getType() {
        return TYPE.SRV;
    }

    @Override
    public int compareTo(SRV other) {
        int res = other.priority - this.priority;
        if (res == 0) {
            res = this.weight - other.weight;
        }
        return res;
    }
}
