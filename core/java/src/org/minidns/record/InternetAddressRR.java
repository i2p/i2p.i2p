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

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * A resource record representing a internet address. Provides {@link #getInetAddress()}.
 */
public abstract class InternetAddressRR<IA extends InetAddress> extends Data {


    /**
     * Target IP.
     */
    protected final byte[] ip;

    /**
     * Cache for the {@link InetAddress} this record presents.
     */
    private transient IA inetAddress;

    protected InternetAddressRR(byte[] ip) {
        this.ip = ip;
    }

    protected InternetAddressRR(IA inetAddress) {
        this(inetAddress.getAddress());
        this.inetAddress = inetAddress;
    }

    @Override
    public final void serialize(DataOutputStream dos) throws IOException {
        dos.write(ip);
    }

    /**
     * Allocates a new byte buffer and fills the buffer with the bytes representing the IP address of this resource record.
     *
     * @return a new byte buffer containing the bytes of the IP.
     */
    public final byte[] getIp() {
        return ip.clone();
    }

    @SuppressWarnings("unchecked")
    public final IA getInetAddress() {
        if (inetAddress == null) {
            try {
                inetAddress = (IA) InetAddress.getByAddress(ip);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
        return inetAddress;
    }

    public static InternetAddressRR<? extends InetAddress> from(InetAddress inetAddress) {
        if (inetAddress instanceof Inet4Address) {
            return new A((Inet4Address) inetAddress);
        }

        return new AAAA((Inet6Address) inetAddress);
    }
}
