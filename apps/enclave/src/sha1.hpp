/*
 * Copyright (c) 2004, Matthew P. Cashdollar <mpc@innographx.com>
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * 
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *     * Neither the name of the author nor the names of any contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef SHA1_HPP
#define SHA1_HPP

class Sha1 {
	public:
		static const size_t SHA1BIN_LEN = 20;

		Sha1(void);
		Sha1(const string& data);
		Sha1(const uchar_t binary[SHA1BIN_LEN]);

		const string& b64hash(void) const { return b64hashed; }
		const uchar_t* binhash(void) const { return binhashed; }
		bool operator<(const Sha1& rhs) const;
		Sha1& operator=(const Sha1& rhs);
		bool operator==(const Sha1& rhs) const;
		void x_or(const Sha1& rhs, Bigint& result) const;

	private:
		void b64(void);

		string b64hashed;  // base 64 of the hash
		uchar_t binhashed[SHA1BIN_LEN];  // non-NUL terminated binary hash
};

#endif  // SHA1_HPP
