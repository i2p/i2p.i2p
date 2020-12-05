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

import org.minidns.dnsname.DnsName;

/**
 * A resource record pointing to a target.
 */
public abstract class RRWithTarget extends Data {

    public final DnsName target;

    /**
     * The target of this resource record.
     * @deprecated {@link #target} instead.
     */
    @Deprecated
    public final DnsName name;

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        target.writeToStream(dos);
    }

    protected RRWithTarget(DnsName target) {
        this.target = target;
        this.name = target;
    }

    @Override
    public String toString() {
        return target + ".";
    }

    public final DnsName getTarget() {
        return target;
    }
}
