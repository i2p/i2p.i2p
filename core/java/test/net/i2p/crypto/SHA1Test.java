package net.i2p.crypto;
/* @(#)SHA1Test.java	1.10 2004-04-24
 * This file was freely contributed to the LimeWire project and is covered
 * by its existing GPL licence, but it may be used individually as a public
 * domain implementation of a published algorithm (see below for references).
 * It was also freely contributed to the Bitzi public domain sources.
 * @author  Philippe Verdy
 */
 
/* Sun may wish to change the following package name, if integrating this
 * class in the Sun JCE Security Provider for Java 1.5 (code-named Tiger).
 */
//package com.bitzi.util;
 
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
 
public class SHA1Test {
    
    private static final SHA1 hash = new SHA1();
 
    public static void main(String args[]) {
// http://csrc.nist.gov/publications/fips/fips180-2/fips180-2.pdf
        System.out.println("****************************************");
        System.out.println("* Basic FIPS PUB 180-1 test vectors... *");
        System.out.println("****************************************");
        tst(1, 1,
            "abc",
            "A9993E36 4706816A BA3E2571 7850C26C 9CD0D89D");
        tst(1, 2,
            "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
            "84983E44 1C3BD26e BAAE4AA1 F95129E5 E54670F1");
        tst(1, 3, /* one million bytes */
            1000000, "a",
            "34AA973C D4C4DAA4 F61EEB2B DBAD2731 6534016F");
        System.out.println();
 
// http://csrc.ncsl.nist.gov/cryptval/shs/SHAVS.pdf
        System.out.println("********************************************************");
        System.out.println("* SHSV Examples of the selected short messages test... *");
        System.out.println("********************************************************");
        tst(2, 2, new byte[] {/* 8 bits, i.e. 1 byte */
            (byte)0x5e},
            "5e6f80a3 4a9798ca fc6a5db9 6cc57ba4 c4db59c2");
        tst(2, 4, new byte[] {/* 128 bits, i.e. 16 bytes */
            (byte)0x9a,(byte)0x7d,(byte)0xfd,(byte)0xf1,(byte)0xec,(byte)0xea,(byte)0xd0,(byte)0x6e,
            (byte)0xd6,(byte)0x46,(byte)0xaa,(byte)0x55,(byte)0xfe,(byte)0x75,(byte)0x71,(byte)0x46},
            "82abff66 05dbe1c1 7def12a3 94fa22a8 2b544a35");
        System.out.println();
 
        System.out.println("*******************************************************");
        System.out.println("* SHSV Examples of the selected long messages test... *");
        System.out.println("*******************************************************");
        tst(3, 2, new byte[] {/* 1304 bits, i.e. 163 bytes */
            (byte)0xf7,(byte)0x8f,(byte)0x92,(byte)0x14,(byte)0x1b,(byte)0xcd,(byte)0x17,(byte)0x0a,
            (byte)0xe8,(byte)0x9b,(byte)0x4f,(byte)0xba,(byte)0x15,(byte)0xa1,(byte)0xd5,(byte)0x9f,
            (byte)0x3f,(byte)0xd8,(byte)0x4d,(byte)0x22,(byte)0x3c,(byte)0x92,(byte)0x51,(byte)0xbd,
            (byte)0xac,(byte)0xbb,(byte)0xae,(byte)0x61,(byte)0xd0,(byte)0x5e,(byte)0xd1,(byte)0x15,
            (byte)0xa0,(byte)0x6a,(byte)0x7c,(byte)0xe1,(byte)0x17,(byte)0xb7,(byte)0xbe,(byte)0xea,
            (byte)0xd2,(byte)0x44,(byte)0x21,(byte)0xde,(byte)0xd9,(byte)0xc3,(byte)0x25,(byte)0x92,
            (byte)0xbd,(byte)0x57,(byte)0xed,(byte)0xea,(byte)0xe3,(byte)0x9c,(byte)0x39,(byte)0xfa,
            (byte)0x1f,(byte)0xe8,(byte)0x94,(byte)0x6a,(byte)0x84,(byte)0xd0,(byte)0xcf,(byte)0x1f,
            (byte)0x7b,(byte)0xee,(byte)0xad,(byte)0x17,(byte)0x13,(byte)0xe2,(byte)0xe0,(byte)0x95,
            (byte)0x98,(byte)0x97,(byte)0x34,(byte)0x7f,(byte)0x67,(byte)0xc8,(byte)0x0b,(byte)0x04,
            (byte)0x00,(byte)0xc2,(byte)0x09,(byte)0x81,(byte)0x5d,(byte)0x6b,(byte)0x10,(byte)0xa6,
            (byte)0x83,(byte)0x83,(byte)0x6f,(byte)0xd5,(byte)0x56,(byte)0x2a,(byte)0x56,(byte)0xca,
            (byte)0xb1,(byte)0xa2,(byte)0x8e,(byte)0x81,(byte)0xb6,(byte)0x57,(byte)0x66,(byte)0x54,
            (byte)0x63,(byte)0x1c,(byte)0xf1,(byte)0x65,(byte)0x66,(byte)0xb8,(byte)0x6e,(byte)0x3b,
            (byte)0x33,(byte)0xa1,(byte)0x08,(byte)0xb0,(byte)0x53,(byte)0x07,(byte)0xc0,(byte)0x0a,
            (byte)0xff,(byte)0x14,(byte)0xa7,(byte)0x68,(byte)0xed,(byte)0x73,(byte)0x50,(byte)0x60,
            (byte)0x6a,(byte)0x0f,(byte)0x85,(byte)0xe6,(byte)0xa9,(byte)0x1d,(byte)0x39,(byte)0x6f,
            (byte)0x5b,(byte)0x5c,(byte)0xbe,(byte)0x57,(byte)0x7f,(byte)0x9b,(byte)0x38,(byte)0x80,
            (byte)0x7c,(byte)0x7d,(byte)0x52,(byte)0x3d,(byte)0x6d,(byte)0x79,(byte)0x2f,(byte)0x6e,
            (byte)0xbc,(byte)0x24,(byte)0xa4,(byte)0xec,(byte)0xf2,(byte)0xb3,(byte)0xa4,(byte)0x27,
            (byte)0xcd,(byte)0xbb,(byte)0xfb},
            "cb0082c8 f197d260 991ba6a4 60e76e20 2bad27b3");
        System.out.println();
 
// See also http://csrc.ncsl.nist.gov/cryptval/shs/sha1-vectors.zip
 
        {
            final int RETRIES = 10;
            final int ITERATIONS = 2000;
            final int BLOCKSIZE = 65536;
            byte[] input = new byte[BLOCKSIZE];
            for (int i = BLOCKSIZE; --i >= 0; )
                input[i] = (byte)i;
            long best = 0;
            for (int i = 0; i < 1000; i++) // training for stable measure
                 System.currentTimeMillis();
 
            for (int retry = 0; retry < RETRIES; retry++) {
                long t0 = System.currentTimeMillis();
                for (int i = ITERATIONS; --i >= 0; );
                long t1 = System.currentTimeMillis();
                for (int i = ITERATIONS; --i >= 0; )
                    hash.engineUpdate(input, 0, BLOCKSIZE);
                long t2 = System.currentTimeMillis();
                long time = (t2 - t1) - (t1 - t0);
                if (retry == 0 || time < best)
                     best = time;
            }
            hash.engineReset();
            double rate = 1000.0 * ITERATIONS * BLOCKSIZE / best;
            System.out.println("Our rate = " +
                               (float)(rate * 8) + " bits/s = " +
                               (float)(rate / (1024 * 1024)) + " Megabytes/s");
            // Java 1.5 beta-b32c, on Athlon XP 1800+:
            // with java -client: 48.21 Megabytes/s.
            // with java -server: 68.23 Megabytes/s.
 
            try {
                MessageDigest md = MessageDigest.getInstance("SHA");
                for (int retry = 0; retry < RETRIES; retry++) {
                    long t0 = System.currentTimeMillis();
                    for (int i = ITERATIONS; --i >= 0; );
                    long t1 = System.currentTimeMillis();
                    for (int i = ITERATIONS; --i >= 0; )
                        md.update(input, 0, BLOCKSIZE);
                    long t2 = System.currentTimeMillis();
                    long time = (t2 - t1) - (t1 - t0);
                    if (retry == 0 || time < best)
                         best = time;
                }
                md.reset();
                rate = 1000.0 * ITERATIONS * BLOCKSIZE / best;
                System.out.println("JCE rate = " +
                                   (float)(rate * 8) + " bits/s = " +
                                   (float)(rate / (1024 * 1024)) + " Megabytes/s");
            } catch (NoSuchAlgorithmException nsae) {
                System.out.println("No SHA algorithm in local JCE Security Providers");
            }
            // Java 1.5 beta-b32c, on Athlon XP 1800+:
            // with java -client: 23.20 Megabytes/s.
            // with java -server: 45.72 Megabytes/s.
        }
    }
    
    private static final boolean tst(final int set, final int vector,
                                     final String source,
                                     final String expect) {
        byte[] input = new byte[source.length()];
        for (int i = 0; i < input.length; i++)
            input[i] = (byte)source.charAt(i);
        return tst(set, vector, input, expect);
    }
 
    private static final boolean tst(final int set, final int vector,
                                     final byte[] input,
                                     final String expect) {
        System.out.print("Set " + set + ", vector# " + vector + ": ");
        hash.engineUpdate(input, 0, input.length);
        return tstResult(expect);
    }
 
    private static final boolean tst(final int set, final int vector,
                                     final int times, final String source,
                                     final String expect) {
        byte[] input = new byte[source.length()];
        for (int i = 0; i < input.length; i++)
            input[i] = (byte)source.charAt(i);
        System.out.print("Set " + set + ", vector# " + vector + ": ");
        for (int i = 0; i < times; i++)
            hash.engineUpdate(input, 0, input.length);
        return tstResult(expect);
    }
 
    private static final boolean tstResult(String expect) {
        final String result = toHex(hash.engineDigest());
        expect = expect.toUpperCase();
        if (!expect.equals(result)) {
            System.out.println("**************** WRONG ***************");
            System.out.println("  expect: " + expect);
            System.out.println("  result: " + result);
            return false;
        }
        System.out.println("OK");
        return true;
    }
 
    private static final String toHex(final byte[] bytes) {
        StringBuilder buf = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            if ((i & 3) == 0 && i != 0)
               buf.append(' ');
            buf.append(HEX.charAt((bytes[i] >> 4) & 0xF))
               .append(HEX.charAt( bytes[i]       & 0xF));
        }
        return buf.toString();
    }
    private static final String HEX = "0123456789ABCDEF";
}
