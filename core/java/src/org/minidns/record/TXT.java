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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.minidns.record.Record.TYPE;

/**
 *  A TXT record. Actually a binary blob containing extents, each of which is a one-byte count
 *  followed by that many bytes of data, which can usually be interpreted as ASCII strings
 *  but not always.
 */
public class TXT extends Data {

    private final byte[] blob;

    public static TXT parse(DataInputStream dis, int length) throws IOException {
        byte[] blob = new byte[length];
        dis.readFully(blob);
        return new TXT(blob);
    }

    public TXT(byte[] blob) {
        this.blob = blob;
    }

    public byte[] getBlob() {
        return blob.clone();
    }

    private transient String textCache;

    public String getText() {
        if (textCache == null) {
            StringBuilder sb = new StringBuilder();
            Iterator<String> it = getCharacterStrings().iterator();
            while (it.hasNext()) {
                sb.append(it.next());
                if (it.hasNext()) {
                    sb.append(" / ");
                }
            }
            textCache = sb.toString();
        }
        return textCache;
    }

    private transient List<String> characterStringsCache;

    public List<String> getCharacterStrings() {
        if (characterStringsCache == null) {
            List<byte[]> extents = getExtents();
            List<String> characterStrings = new ArrayList<>(extents.size());
            for (byte[] extent : extents) {
                try {
                    characterStrings.add(new String(extent, "UTF-8"));
                } catch (UnsupportedEncodingException e) {
                    throw new AssertionError(e);
                }
            }

            characterStringsCache = Collections.unmodifiableList(characterStrings);
        }
        return characterStringsCache;
    }

    public List<byte[]> getExtents() {
        ArrayList<byte[]> extents = new ArrayList<byte[]>();
        int segLength = 0;
        for (int used = 0; used < blob.length; used += segLength) {
            segLength = 0x00ff & blob[used];
            int end = ++used + segLength;
            byte[] extent = Arrays.copyOfRange(blob, used, end);
            extents.add(extent);
        }
        return extents;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        dos.write(blob);
    }

    @Override
    public TYPE getType() {
        return TYPE.TXT;
    }

    @Override
    public String toString() {
        return "\"" + getText() + "\"";
    }

}
