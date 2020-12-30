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
import java.net.Inet6Address;

import org.minidns.record.Record.TYPE;
import org.minidns.util.InetAddressUtil;

/**
 * AAAA payload (an ipv6 pointer).
 */
public class AAAA extends InternetAddressRR<Inet6Address> {

    @Override
    public TYPE getType() {
        return TYPE.AAAA;
    }

    public AAAA(Inet6Address inet6address) {
        super(inet6address);
        assert ip.length == 16;
    }

    public AAAA(byte[] ip) {
        super(ip);
        if (ip.length != 16) {
            throw new IllegalArgumentException("IPv6 address in AAAA record is always 16 byte");
        }
    }

    public AAAA(CharSequence ipv6CharSequence) {
        this(InetAddressUtil.ipv6From(ipv6CharSequence));
    }

    public static AAAA parse(DataInputStream dis)
            throws IOException {
        byte[] ip = new byte[16];
        dis.readFully(ip);
        return new AAAA(ip);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ip.length; i += 2) {
            if (i != 0) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(
                ((ip[i] & 0xff) << 8) + (ip[i + 1] & 0xff)
            ));
        }
        return sb.toString();
    }

}
