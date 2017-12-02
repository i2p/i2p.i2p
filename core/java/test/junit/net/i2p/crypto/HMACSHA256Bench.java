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

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.SessionKey;

public class HMACSHA256Bench {
        public static void main(String args[]) {
            runTest(new I2PAppContext());
            System.out.println("Running as MD5");
            Properties props = new Properties();
            //props.setProperty("i2p.fakeHMAC", "true");
            props.setProperty("i2p.HMACMD5", "true");
            runTest(new I2PAppContext(props));
        }
        private static void runTest(I2PAppContext ctx) {
        SessionKey key = ctx.keyGenerator().generateSessionKey();
        byte[] output = new byte[32];
		ctx.hmac().calculate(key, "qwerty".getBytes(), 0, 6, output, 0);
			
		int times = 100000;
		long shorttime = 0;
		long medtime = 0;
		long longtime = 0;
		long minShort = 0;
		long maxShort = 0;
		long minMed = 0;
		long maxMed = 0;
		long minLong = 0;
		long maxLong = 0;
        
		long shorttime1 = 0;
		long medtime1 = 0;
		long longtime1 = 0;
		long minShort1 = 0;
		long maxShort1 = 0;
		long minMed1 = 0;
		long maxMed1 = 0;
		long minLong1 = 0;
		long maxLong1 = 0;
        
		byte[] smess = "abc".getBytes();
		StringBuilder buf = new StringBuilder();
		for (int x = 0; x < 2*1024; x++) {
			buf.append("a");
		}
		byte[] mmess = DataHelper.getASCII(buf.toString()); // new String("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq").getBytes();
		buf = new StringBuilder();
		for (int x = 0; x < 10000; x++) {
			buf.append("a");
		}
		byte[] lmess = DataHelper.getASCII(buf.toString());

		// warm up the engines
        ctx.hmac().calculate(key, smess, 0, smess.length, output, 0);
        ctx.hmac().calculate(key, mmess, 0, mmess.length, output, 0);
        ctx.hmac().calculate(key, lmess, 0, lmess.length, output, 0);
        
        long before = System.currentTimeMillis();
        for (int x = 0; x < times; x++)
            ctx.hmac().calculate(key, smess, 0, smess.length, output, 0);
        long after = System.currentTimeMillis();
        
        display(times, before, after, smess.length, "3 byte");
        
        before = System.currentTimeMillis();
        for (int x = 0; x < times; x++)
            ctx.hmac().calculate(key, mmess, 0, mmess.length, output, 0);
        after = System.currentTimeMillis();

        display(times, before, after, mmess.length, "2KB");
        
        before = System.currentTimeMillis();
        for (int x = 0; x < times; x++)
            ctx.hmac().calculate(key, lmess, 0, lmess.length, output, 0);
        after = System.currentTimeMillis();

        display(times, before, after, lmess.length, "10KB");
	}
    
    private static void display(int times, long before, long after, int len, String name) {
        double rate = times/(((double)after-(double)before)/1000.0d);
        double kbps = (len/1024.0d)*times/(((double)after-(double)before)/1000.0d);
        System.out.println(name + " HMAC pulled " + kbps + "KBps or " + rate + " calcs per second");
    }
}
	
