package net.i2p.crypto;

/*
 * Copyright (c) 2003, TheCrypto
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * -  Neither the name of the TheCrypto may be used to endorse or promote
 *    products derived from this software without specific prior written
 *    permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;

public class AES256Bench {
    private static I2PAppContext _context = new I2PAppContext();
    
    public static void main(String args[]) {
        char[] cplain = {
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff,
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff
        };
        
        byte[] plain = new byte[cplain.length];
        for (int x = 0; x < cplain.length; x++) {
            plain[x] = (byte)cplain[x];
        }
        char[] ckey = {
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
        };
        byte[] bkey = new byte[ckey.length];
        for (int x = 0; x < ckey.length; x++) {
            bkey[x] = (byte)ckey[x];
        }
        
        SessionKey key = new SessionKey();
        key.setData(bkey);
        
        char[] civ = {
            0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef,
            0xfe, 0xdc, 0xba, 0x98, 0x67, 0x54, 0x32, 0x10
        };
        
        byte[] iv = new byte[civ.length];
        for (int x = 0; x < iv.length; x++) {
            iv[x] = (byte)civ[x];
        }
        
        byte[] e = new byte[plain.length];
        _context.aes().encrypt(plain, 0, e, 0, key, iv, plain.length);
        byte[] d = new byte[e.length];
        _context.aes().decrypt(e, 0, d, 0, key, iv, d.length);
        boolean same = true;
        for (int x = 0; x < d.length; x++) {
            if (plain[x] != d[x]) {
                throw new RuntimeException("Failed decrypt at " + x);
            }
        }
        
        System.out.println("Standard test D(E(value)) == value? " + same);
        if (!same) throw new RuntimeException("moo");
        
        plain = "1234567890123456".getBytes();
        e = new byte[plain.length];
        _context.aes().encrypt(plain, 0, e, 0, key, iv, plain.length);
        d = new byte[e.length];
        _context.aes().decrypt(e, 0, d, 0, key, iv, d.length);
        same = DataHelper.eq(plain, d);
        System.out.println("Different value test D(E(value)) == value? " + same);
        if (!same) throw new RuntimeException("moo");
        
        System.out.println();
        System.out.println();
        
        long times = 100;
        long encrypttime = 0;
        long decrypttime = 0;
        long maxE = 0;
        long minE = 0;
        long maxD = 0;
        long minD = 0;
        byte[] message = new byte[2*1024];
        for (int i = 0; i < message.length; i++)
            message[i] = (byte)((i%26)+'a');
        for (int x = 0; x < times; x++) {
            long startencrypt = System.currentTimeMillis();
            e = new byte[message.length];
            d = new byte[e.length];
            _context.aes().encrypt(message, 0, e, 0, key, iv, message.length);
            long endencryptstartdecrypt = System.currentTimeMillis();
            _context.aes().decrypt(e, 0, d, 0, key, iv, d.length);
            long enddecrypt = System.currentTimeMillis();
            System.out.print(".");
            encrypttime += endencryptstartdecrypt - startencrypt;
            decrypttime += enddecrypt - endencryptstartdecrypt;
            if (!DataHelper.eq(d, message)) {
                System.out.println("Lengths: source [" + message.length + "] dest [" + d.length + "]");
                System.out.println("Data: dest [" + DataHelper.toString(d, d.length) + "]");
                throw new RuntimeException("Holy crap, decrypted != source message");
            }
            
            if ( (minE == 0) && (minD == 0) ) {
                minE = endencryptstartdecrypt - startencrypt;
                maxE = endencryptstartdecrypt - startencrypt;
                minD = enddecrypt - endencryptstartdecrypt;
                maxD = enddecrypt - endencryptstartdecrypt;
            } else {
                if (minE > endencryptstartdecrypt - startencrypt) minE = endencryptstartdecrypt - startencrypt;
                if (maxE < endencryptstartdecrypt - startencrypt) maxE = endencryptstartdecrypt - startencrypt;
                if (minD > enddecrypt - endencryptstartdecrypt) minD = enddecrypt - endencryptstartdecrypt;
                if (maxD < enddecrypt - endencryptstartdecrypt) maxD = enddecrypt - endencryptstartdecrypt;
            }
            
        }
        
        System.out.println();
        System.out.println("Data size                  : " + message.length);
        System.out.println("Encryption Time Average    : " + (encrypttime/times) + "ms\ttotal: " + encrypttime + "ms\tmin: " + minE + "ms\tmax: " + maxE + "ms\tEncryption Bps: " + (times*message.length*1000)/encrypttime);
        System.out.println("Decryption Time Average    : " + (decrypttime/times) + "ms\ttotal: " + decrypttime + "ms\tmin: " + minD + "ms\tmax: " + maxD + "ms\tDecryption Bps: " + (times*message.length*1000)/decrypttime);
    }
}

