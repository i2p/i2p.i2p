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
package org.minidns.edns;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.minidns.dnsname.DnsName;
import org.minidns.record.Data;
import org.minidns.record.OPT;
import org.minidns.record.Record;
import org.minidns.record.Record.TYPE;

/**
 * EDNS - Extension Mechanism for DNS.
 *
 * @see <a href="https://tools.ietf.org/html/rfc6891">RFC 6891 - Extension Mechanisms for DNS (EDNS(0))</a>
 *
 */
public class Edns {

    /**
     * Inform the dns server that the client supports DNSSEC.
     */
    public static final int FLAG_DNSSEC_OK = 0x8000;

    /**
     * The EDNS option code.
     *
     * @see <a href="http://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-11">IANA - DNS EDNS0 Option Codes (OPT)</a>
     */
    public enum OptionCode {
        UNKNOWN(-1, UnknownEdnsOption.class),
        NSID(3, Nsid.class),
        ;

        private static Map<Integer, OptionCode> INVERSE_LUT = new HashMap<>(OptionCode.values().length);

        static {
            for (OptionCode optionCode : OptionCode.values()) {
                INVERSE_LUT.put(optionCode.asInt, optionCode);
            }
        }

        public final int asInt;
        public final Class<? extends EdnsOption> clazz;

        OptionCode(int optionCode, Class<? extends EdnsOption> clazz) {
            this.asInt = optionCode;
            this.clazz = clazz;
        }

        public static OptionCode from(int optionCode) {
            OptionCode res = INVERSE_LUT.get(optionCode);
            if (res == null) res = OptionCode.UNKNOWN;
            return res;
        }
    }

    public final int udpPayloadSize;

    /**
     * 8-bit extended return code.
     *
     * RFC 6891 § 6.1.3 EXTENDED-RCODE 
     */
    public final int extendedRcode;

    /**
     * 8-bit version field.
     *
     * RFC 6891 § 6.1.3 VERSION
     */
    public final int version;

    /**
     * 16-bit flags.
     *
     * RFC 6891 § 6.1.4
     */
    public final int flags;

    public final List<EdnsOption> variablePart;

    public final boolean dnssecOk;

    private Record<OPT> optRecord;

    public Edns(Record<OPT> optRecord) {
        assert optRecord.type == TYPE.OPT;
        udpPayloadSize = optRecord.clazzValue;
        extendedRcode = (int) ((optRecord.ttl >> 8) & 0xff);
        version = (int) ((optRecord.ttl >> 16) & 0xff);
        flags = (int) optRecord.ttl & 0xffff;

        dnssecOk = (optRecord.ttl & FLAG_DNSSEC_OK) > 0;

        OPT opt = optRecord.payloadData;
        variablePart = opt.variablePart;
        this.optRecord = optRecord;
    }

    public Edns(Builder builder) {
        udpPayloadSize = builder.udpPayloadSize;
        extendedRcode = builder.extendedRcode;
        version = builder.version;
        int flags = 0;
        if (builder.dnssecOk) {
            flags |= FLAG_DNSSEC_OK;
        }
        dnssecOk = builder.dnssecOk;
        this.flags = flags;
        if (builder.variablePart != null) {
            variablePart = builder.variablePart;
        } else {
            variablePart = Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public <O extends EdnsOption> O getEdnsOption(OptionCode optionCode) {
        for (EdnsOption o : variablePart) {
            if (o.getOptionCode().equals(optionCode)) {
                return (O) o;
            }
        }
        return null;
    }

    public Record<OPT> asRecord() {
        if (optRecord == null) {
            long optFlags = flags;
            optFlags |= extendedRcode << 8;
            optFlags |= version << 16;
            optRecord = new Record<OPT>(DnsName.ROOT, Record.TYPE.OPT, udpPayloadSize, optFlags, new OPT(variablePart));
        }
        return optRecord;
    }

    private String terminalOutputCache;

    public String asTerminalOutput() {
        if (terminalOutputCache == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("EDNS: version: ").append(version).append(", flags:");
            if (dnssecOk)
                sb.append(" do");
            sb.append("; udp: ").append(udpPayloadSize);
            if (!variablePart.isEmpty()) {
                sb.append('\n');
                Iterator<EdnsOption> it = variablePart.iterator();
                while (it.hasNext()) {
                    EdnsOption edns = it.next();
                    sb.append(edns.getOptionCode()).append(": ");
                    sb.append(edns.asTerminalOutput());
                    if (it.hasNext()) {
                        sb.append('\n');
                    }
                }
            }
            terminalOutputCache = sb.toString();
        }
        return terminalOutputCache;
    }

    @Override
    public String toString() {
        return asTerminalOutput();
    }

    public static Edns fromRecord(Record<? extends Data> record) {
        if (record.type != TYPE.OPT) return null;

        @SuppressWarnings("unchecked")
        Record<OPT> optRecord = (Record<OPT>) record;
        return new Edns(optRecord);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int udpPayloadSize;
        private int extendedRcode;
        private int version;
        private boolean dnssecOk;
        private List<EdnsOption> variablePart;

        private Builder() {
        }

        public Builder setUdpPayloadSize(int udpPayloadSize) {
            if (udpPayloadSize > 0xffff) {
                throw new IllegalArgumentException("UDP payload size must not be greater than 65536, was " + udpPayloadSize);
            }
            this.udpPayloadSize = udpPayloadSize;
            return this;
        }

        public Builder setDnssecOk(boolean dnssecOk) {
            this.dnssecOk = dnssecOk;
            return this;
        }

        public Builder setDnssecOk() {
            dnssecOk = true;
            return this;
        }

        public Builder addEdnsOption(EdnsOption ednsOption) {
            if (variablePart == null) {
                variablePart = new ArrayList<>(4);
            }
            variablePart.add(ednsOption);
            return this;
        }

        public Edns build() {
            return new Edns(this);
        }
    }
}
