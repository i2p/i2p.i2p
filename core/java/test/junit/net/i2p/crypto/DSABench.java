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

import java.io.ByteArrayInputStream;

import net.i2p.data.Signature;
import net.i2p.data.SigningPrivateKey;
import net.i2p.data.SigningPublicKey;

public class DSABench {
	public static void main(String args[]) {
		int times = 100;
		long keygentime = 0;
		long signtime = 0;
		long verifytime = 0;
		long maxKey = 0;
		long minKey = 0;
		long maxS = 0;
		long minS = 0;
		long maxV = 0;
		long minV = 0;
		Object[] keys = KeyGenerator.getInstance().generateSigningKeypair();
		byte[] message = new byte[32+32];
		for (int i = 0; i < message.length; i++)
		    message[i] = (byte)((i%26)+'a');
		for (int x = 0; x < times; x++) {	
			long startkeys = System.currentTimeMillis();
			keys = KeyGenerator.getInstance().generateSigningKeypair();
			SigningPublicKey pubkey = (SigningPublicKey)keys[0];
			SigningPrivateKey privkey = (SigningPrivateKey)keys[1];
			long endkeys = System.currentTimeMillis();
			long startsign = System.currentTimeMillis();
			Signature s = DSAEngine.getInstance().sign(message, privkey);
            Signature s1 = DSAEngine.getInstance().sign(new ByteArrayInputStream(message), privkey);
			long endsignstartverify = System.currentTimeMillis();
			boolean v = DSAEngine.getInstance().verifySignature(s, message, pubkey);
            boolean v1 = DSAEngine.getInstance().verifySignature(s1, new ByteArrayInputStream(message), pubkey);
			boolean v2 = DSAEngine.getInstance().verifySignature(s1, message, pubkey);
            boolean v3 = DSAEngine.getInstance().verifySignature(s, new ByteArrayInputStream(message), pubkey);
            long endverify = System.currentTimeMillis();
			System.out.print(".");
			keygentime += endkeys - startkeys;
			signtime += endsignstartverify - startsign;
			verifytime += endverify - endsignstartverify;
			if (!v) {
			    throw new RuntimeException("Holy crap, did not verify");
			}
            if (!(v1 && v2 && v3))
                throw new RuntimeException("Stream did not verify");
			if ( (minKey == 0) && (minS == 0) && (minV == 0) ) {
			    minKey = endkeys - startkeys;
			    maxKey = endkeys - startkeys;
			    minS = endsignstartverify - startsign;
			    maxS = endsignstartverify - startsign;
			    minV = endverify - endsignstartverify;
			    maxV = endverify - endsignstartverify;
			} else {
			    if (minKey > endkeys - startkeys) minKey = endkeys - startkeys;
			    if (maxKey < endkeys - startkeys) maxKey = endkeys - startkeys;
			    if (minS > endsignstartverify - startsign) minS = endsignstartverify - startsign;
			    if (maxS < endsignstartverify - startsign) maxS = endsignstartverify - startsign;
			    if (minV > endverify - endsignstartverify) minV = endverify - endsignstartverify;
			    if (maxV < endverify - endsignstartverify) maxV = endverify - endsignstartverify;
			}
		}
		System.out.println();
		System.out.println("Key Generation Time Average: " + (keygentime/times) + "\ttotal: " + keygentime + "\tmin: " + minKey + "\tmax: " + maxKey  + "\tKeygen/second: " + (keygentime == 0 ? "NaN" : ""+(times*1000)/keygentime));
		System.out.println("Signing Time Average       : " + (signtime/times) + "\ttotal: " + signtime + "\tmin: " + minS + "\tmax: " + maxS + "\tSigning Bps: " + (times*message.length*1000)/signtime);
		System.out.println("Verification Time Average  : " + (verifytime/times) + "\ttotal: " + verifytime + "\tmin: " + minV + "\tmax: " + maxV + "\tDecryption Bps: " + (times*message.length*1000)/verifytime);
	}
}
	
