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

import net.i2p.data.Hash;

public class SHA256Bench {
	public static void main(String args[]) {
		Hash asdfs = SHA256Generator.getInstance().calculateHash("qwerty".getBytes());
			
		int times = 100;
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
        
		byte[] smess = new String("abc").getBytes();
		StringBuffer buf = new StringBuffer();
		for (int x = 0; x < 10*1024; x++) {
			buf.append("a");
		}
		byte[] mmess = buf.toString().getBytes(); // new String("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq").getBytes();
		buf = new StringBuffer();
		for (int x = 0; x < 1000000; x++) {
			buf.append("a");
		}
		byte[] lmess = buf.toString().getBytes();
        
        SHA256EntryCache cache = new SHA256EntryCache();
        SHA256EntryCache.CacheEntry scache = cache.acquire(smess.length);
        SHA256EntryCache.CacheEntry mcache = cache.acquire(mmess.length);
        SHA256EntryCache.CacheEntry lcache = cache.acquire(lmess.length);
        
		// warm up the engines
		SHA256Generator.getInstance().calculateHash(smess);
		SHA256Generator.getInstance().calculateHash(mmess);
		SHA256Generator.getInstance().calculateHash(lmess);
		// now do it
		for (int x = 0; x < times; x++) {	
			long startshort = System.currentTimeMillis();
            boolean cacheOnly = false;
            // no caching
			Hash s = cacheOnly ? null : SHA256Generator.getInstance().calculateHash(smess);
			long endshortstartmed = System.currentTimeMillis();
			Hash m = cacheOnly ? null : SHA256Generator.getInstance().calculateHash(mmess);
			long endmedstartlong = System.currentTimeMillis();
			Hash l = cacheOnly ? null : SHA256Generator.getInstance().calculateHash(lmess);
			long endlong = System.currentTimeMillis();
            
            // now do it with caching
			Hash s1 = SHA256Generator.getInstance().calculateHash(smess, 0, smess.length, scache);
			long endshortstartmed1 = System.currentTimeMillis();
			Hash m1 = SHA256Generator.getInstance().calculateHash(mmess, 0, mmess.length, mcache);
			long endmedstartlong1 = System.currentTimeMillis();
			Hash l1 = SHA256Generator.getInstance().calculateHash(lmess, 0, lmess.length, lcache);
			long endlong1 = System.currentTimeMillis();
            
            if (cacheOnly) {
                // dont verify
            } else {
                if (!s.equals(s1) || !m.equals(m1) || !l.equals(l1)) {
                    System.err.println("*ERR* match failed on " + x 
                                       + "s="+ s.equals(s1) + " m=" + m.equals(m1)
                                       + " l=" + l.equals(l1));
                    return;
                }
            }
            
			//System.out.print(".");
			shorttime += endshortstartmed - startshort;
			medtime += endmedstartlong - endshortstartmed;
			longtime += endlong - endmedstartlong;
            
			shorttime1 += endshortstartmed1 - endlong;
			medtime1 += endmedstartlong1 - endshortstartmed1;
			longtime1 += endlong1 - endmedstartlong1;
			
			if ((minShort == 0) && (minMed == 0) && (minLong == 0) ) {
			    minShort = endshortstartmed - startshort;
			    maxShort = endshortstartmed - startshort;
			    minMed = endmedstartlong - endshortstartmed;
			    maxMed = endmedstartlong - endshortstartmed;
			    minLong = endlong - endmedstartlong;
			    maxLong = endlong - endmedstartlong;
                
			    minShort1 = endshortstartmed1 - endlong;
			    maxShort1 = endshortstartmed1 - endlong;
			    minMed1 = endmedstartlong1 - endshortstartmed1;
			    maxMed1 = endmedstartlong1 - endshortstartmed1;
			    minLong1 = endlong1 - endmedstartlong1;
			    maxLong1 = endlong1 - endmedstartlong1;
			} else {
			    if (minShort > endshortstartmed - startshort) minShort = endshortstartmed - startshort;
			    if (maxShort < endshortstartmed - startshort) maxShort = endshortstartmed - startshort;
			    if (minMed > endmedstartlong - endshortstartmed) minMed = endmedstartlong - endshortstartmed;
			    if (maxMed < endmedstartlong - endshortstartmed) maxMed = endmedstartlong - endshortstartmed;
			    if (minLong > endlong - endmedstartlong) minLong = endlong - endmedstartlong;
			    if (maxLong < endlong - endmedstartlong) maxLong = endlong - endmedstartlong;
                
			    if (minShort1 > endshortstartmed1 - endlong) minShort1 = endshortstartmed1 - endlong;
			    if (maxShort1 < endshortstartmed1 - endlong) maxShort1 = endshortstartmed1 - endlong;
			    if (minMed1 > endmedstartlong1 - endshortstartmed1) minMed1 = endmedstartlong - endshortstartmed;
			    if (maxMed1 < endmedstartlong1 - endshortstartmed1) maxMed1 = endmedstartlong - endshortstartmed;
			    if (minLong1 > endlong1 - endmedstartlong1) minLong1 = endlong1 - endmedstartlong1;
			    if (maxLong1 < endlong1 - endmedstartlong1) maxLong1 = endlong1 - endmedstartlong1;
			}
		}
		System.out.println();
		System.out.println("Short Message Time Average  : " + (shorttime/times) + "\ttotal: " + shorttime + "\tmin: " + minShort + "\tmax: " + maxShort + "\tBps: " + (shorttime == 0 ? "NaN" : ""+(times*smess.length)/shorttime));
		System.out.println("Medium Message Time Average : " + (medtime/times) + "\ttotal: " + medtime + "\tmin: " + minMed + "\tmax: " + maxMed + "\tBps: " + (medtime == 0 ? "NaN" : ""+(times*mmess.length*1000)/medtime));
		System.out.println("Long Message Time Average   : " + (longtime/times) + "\ttotal: " + longtime + "\tmin: " + minLong + "\tmax: " + maxLong + "\tBps: " + (longtime == 0 ? "NaN" : "" + (times*lmess.length*1000)/longtime));
        
		System.out.println("Short Message Time (cache)  : " + (shorttime1/times) + "\ttotal: " + shorttime1 + "\tmin: " + minShort1 + "\tmax: " + maxShort1 + "\tBps: " + (shorttime1 == 0 ? "NaN" : ""+(times*smess.length)/shorttime1));
		System.out.println("Medium Message Time (cache) : " + (medtime1/times) + "\ttotal: " + medtime1 + "\tmin: " + minMed1 + "\tmax: " + maxMed1 + "\tBps: " + (times*mmess.length*1000)/medtime1);
		System.out.println("Long Message Time (cache)   : " + (longtime1/times) + "\ttotal: " + longtime1 + "\tmin: " + minLong1 + "\tmax: " + maxLong1 + "\tBps: " + (times*lmess.length*1000)/longtime1);
	}
}
	
