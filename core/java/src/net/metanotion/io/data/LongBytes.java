/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.io.data;

import net.metanotion.io.Serializer;

public class LongBytes implements Serializer {
	public byte[] getBytes(Object o) {
		byte[] b = new byte[8];
		long v = ((Long) o).longValue();
 		b[0] = (byte)(0xff & (v >> 56));
		b[1] = (byte)(0xff & (v >> 48));
 		b[2] = (byte)(0xff & (v >> 40));
 		b[3] = (byte)(0xff & (v >> 32));
 		b[4] = (byte)(0xff & (v >> 24));
 		b[5] = (byte)(0xff & (v >> 16));
		b[6] = (byte)(0xff & (v >>  8));
 		b[7] = (byte)(0xff & v);
 		return b;
	}

	public Object construct(byte[] b) {
		long v =(((long)(b[0] & 0xff) << 56) |
				 ((long)(b[1] & 0xff) << 48) |
				 ((long)(b[2] & 0xff) << 40) |
				 ((long)(b[3] & 0xff) << 32) |
				 ((long)(b[4] & 0xff) << 24) |
				 ((long)(b[5] & 0xff) << 16) |
				 ((long)(b[6] & 0xff) <<  8) |
				 ((long)(b[7] & 0xff)));
		return new Long(v);
	}
}
