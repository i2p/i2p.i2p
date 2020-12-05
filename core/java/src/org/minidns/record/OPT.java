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

import org.minidns.edns.EdnsOption;
import org.minidns.record.Record.TYPE;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OPT payload (see RFC 2671 for details).
 */
public class OPT extends Data {

    public final List<EdnsOption> variablePart;

    public OPT() {
        this(Collections.<EdnsOption>emptyList());
    }

    public OPT(List<EdnsOption> variablePart) {
        this.variablePart = Collections.unmodifiableList(variablePart);
    }

    public static OPT parse(DataInputStream dis, int payloadLength) throws IOException {
        List<EdnsOption> variablePart;
        if (payloadLength == 0) {
            variablePart = Collections.emptyList();
        } else {
            int payloadLeft = payloadLength;
            variablePart = new ArrayList<>(4);
            while (payloadLeft > 0) {
                int optionCode = dis.readUnsignedShort();
                int optionLength = dis.readUnsignedShort();
                byte[] optionData = new byte[optionLength];
                dis.read(optionData);
                EdnsOption ednsOption = EdnsOption.parse(optionCode, optionData);
                variablePart.add(ednsOption);
                payloadLeft -= 2 + 2 + optionLength;
                // Assert that payloadLeft never becomes negative
                assert payloadLeft >= 0;
            }
        }
        return new OPT(variablePart);
    }

    @Override
    public TYPE getType() {
        return TYPE.OPT;
    }

    @Override
    protected void serialize(DataOutputStream dos) throws IOException {
        for (EdnsOption endsOption : variablePart) {
            endsOption.writeToDos(dos);
        }
    }

}
