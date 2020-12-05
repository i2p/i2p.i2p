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
import java.io.IOException;

import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.TYPE;

/**
 * A PTR record is handled like a CNAME.
 */
public class PTR extends RRWithTarget {

    public static PTR parse(DataInputStream dis, byte[] data) throws IOException {
        DnsName target = DnsName.parse(dis, data);
        return new PTR(target);
    }

    PTR(String name) {
        this(DnsName.from(name));
    }

    PTR(DnsName name) {
        super(name);
    }

    @Override
    public TYPE getType() {
        return TYPE.PTR;
    }

}
