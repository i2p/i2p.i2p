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

import org.minidns.dnsname.DnsName;
import org.minidns.record.Record.TYPE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * NSEC record payload.
 */
public class NSEC extends Data {

    private static final Logger LOGGER = Logger.getLogger(NSEC.class.getName());

    /**
     * The next owner name that contains a authoritative data or a delegation point.
     */
    public final DnsName next;

    private final byte[] typeBitmap;

    /**
     * The RR types existing at the owner name.
     */
    public final List<TYPE> types;

    public static NSEC parse(DataInputStream dis, byte[] data, int length) throws IOException {
        DnsName next = DnsName.parse(dis, data);

        byte[] typeBitmap = new byte[length - next.size()];
        if (dis.read(typeBitmap) != typeBitmap.length) throw new IOException();
        List<TYPE> types = readTypeBitMap(typeBitmap);
        return new NSEC(next, types);
    }

    public NSEC(String next, List<TYPE> types) {
        this(DnsName.from(next), types);
    }

    public NSEC(String next, TYPE... types) {
        this(DnsName.from(next), Arrays.asList(types));
    }

    public NSEC(DnsName next, List<TYPE> types) {
        this.next = next;
        this.types = Collections.unmodifiableList(types);
        this.typeBitmap = createTypeBitMap(types);
    }

    @Override
    public TYPE getType() {
        return TYPE.NSEC;
    }

    @Override
    public void serialize(DataOutputStream dos) throws IOException {
        next.writeToStream(dos);
        dos.write(typeBitmap);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder()
                .append(next).append('.');
        for (TYPE type : types) {
            sb.append(' ').append(type);
        }
        return sb.toString();
    }

    @SuppressWarnings("NarrowingCompoundAssignment")
    static byte[] createTypeBitMap(List<TYPE> types) {
        List<Integer> typeList = new ArrayList<Integer>(types.size());
        for (TYPE type : types) {
            typeList.add(type.getValue());
        }
        Collections.sort(typeList);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        try {
            int windowBlock = -1;
            byte[] bitmap = null;
            for (Integer type : typeList) {
                if (windowBlock == -1 || (type >> 8) != windowBlock) {
                    if (windowBlock != -1) writeOutBlock(bitmap, dos);
                    windowBlock = type >> 8;
                    dos.writeByte(windowBlock);
                    bitmap = new byte[32];
                }
                int a = (type >> 3) % 32;
                int b = type % 8;
                bitmap[a] |= 128 >> b;
            }
            if (windowBlock != -1) writeOutBlock(bitmap, dos);
        } catch (IOException e) {
            // Should never happen.
            throw new RuntimeException(e);
        }

        return baos.toByteArray();
    }

    private static void writeOutBlock(byte[] values, DataOutputStream dos) throws IOException {
        int n = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] != 0) n = i + 1;
        }
        dos.writeByte(n);
        for (int i = 0; i < n; i++) {
            dos.writeByte(values[i]);
        }
    }

    // TODO: This method should probably just return List<Integer> so that unknown types can be act on later.
    static List<TYPE> readTypeBitMap(byte[] typeBitmap) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(typeBitmap));
        int read = 0;
        ArrayList<TYPE> typeList = new ArrayList<TYPE>();
        while (typeBitmap.length > read) {
            int windowBlock = dis.readUnsignedByte();
            int bitmapLength = dis.readUnsignedByte();
            for (int i = 0; i < bitmapLength; i++) {
                int b = dis.readUnsignedByte();
                for (int j = 0; j < 8; j++) {
                    if (((b >> j) & 0x1) > 0) {
                        int typeInt = (windowBlock << 8) + (i * 8) + (7 - j);
                        TYPE type = TYPE.getType(typeInt);
                        if (type == TYPE.UNKNOWN) {
                            LOGGER.warning("Skipping unknown type in type bitmap: " + typeInt);
                            continue;
                        }
                        typeList.add(type);
                    }
                }
            }
            read += bitmapLength + 2;
        }
        return typeList;
    }
}
