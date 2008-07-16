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
import net.i2p.data.PrivateKey;
import net.i2p.data.PublicKey;

public class ElGamalBench {
    private static I2PAppContext _context = new I2PAppContext();
    public static void main(String args[]) {
        int times = 100;
        long keygentime = 0;
        long encrypttime = 0;
        long decrypttime = 0;
        long maxKey = 0;
        long minKey = 0;
        long maxE = 0;
        long minE = 0;
        long maxD = 0;
        long minD = 0;
        Object[] keys = KeyGenerator.getInstance().generatePKIKeypair();
        byte[] message = new byte[222];
        for (int i = 0; i < message.length; i++)
            message[i] = (byte)((i%26)+'a');
        for (int x = 0; x < times; x++) {
            long startkeys = System.currentTimeMillis();
            keys = KeyGenerator.getInstance().generatePKIKeypair();
            PublicKey pubkey = (PublicKey)keys[0];
            PrivateKey privkey = (PrivateKey)keys[1];
            long endkeys = System.currentTimeMillis();
            long startencrypt = System.currentTimeMillis();
            byte[] e = _context.elGamalEngine().encrypt(message, pubkey);
            long endencryptstartdecrypt = System.currentTimeMillis();
            byte[] d = _context.elGamalEngine().decrypt(e, privkey);
            long enddecrypt = System.currentTimeMillis();
            System.out.print(".");
            keygentime += endkeys - startkeys;
            encrypttime += endencryptstartdecrypt - startencrypt;
            decrypttime += enddecrypt - endencryptstartdecrypt;
            if (!DataHelper.eq(d, message)) {
                System.out.println("Lengths: source [" + message.length + "] dest [" + d.length + "]");
                byte hash1[] = SHA256Generator.getInstance().calculateHash(message).getData();
                byte hash2[] = SHA256Generator.getInstance().calculateHash(d).getData();
                System.out.println("Hashes: source [" + DataHelper.toString(hash1, hash1.length) + "] dest [" + DataHelper.toString(hash2, hash2.length) + "]");
                throw new RuntimeException("Holy crap, decrypted != source message");
            }
            if ( (minKey == 0) && (minE == 0) && (minD == 0) ) {
                minKey = endkeys - startkeys;
                maxKey = endkeys - startkeys;
                minE = endencryptstartdecrypt - startencrypt;
                maxE = endencryptstartdecrypt - startencrypt;
                minD = enddecrypt - endencryptstartdecrypt;
                maxD = enddecrypt - endencryptstartdecrypt;
            } else {
                if (minKey > endkeys - startkeys) minKey = endkeys - startkeys;
                if (maxKey < endkeys - startkeys) maxKey = endkeys - startkeys;
                if (minE > endencryptstartdecrypt - startencrypt) minE = endencryptstartdecrypt - startencrypt;
                if (maxE < endencryptstartdecrypt - startencrypt) maxE = endencryptstartdecrypt - startencrypt;
                if (minD > enddecrypt - endencryptstartdecrypt) minD = enddecrypt - endencryptstartdecrypt;
                if (maxD < enddecrypt - endencryptstartdecrypt) maxD = enddecrypt - endencryptstartdecrypt;
            }
        }
        System.out.println();
        System.out.println("Key Generation Time Average: " + (keygentime/times) + "\ttotal: " + keygentime + "\tmin: " + minKey + "\tmax: " + maxKey  + "\tKeygen/second: " + (keygentime == 0 ? "NaN" : ""+(times*1000)/keygentime));
        System.out.println("Encryption Time Average    : " + (encrypttime/times) + "\ttotal: " + encrypttime + "\tmin: " + minE + "\tmax: " + maxE + "\tEncryption Bps: " + (times*message.length*1000)/encrypttime);
        System.out.println("Decryption Time Average    : " + (decrypttime/times) + "\ttotal: " + decrypttime + "\tmin: " + minD + "\tmax: " + maxD + "\tDecryption Bps: " + (times*message.length*1000)/decrypttime);
    }
}

