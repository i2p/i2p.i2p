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

#include "platform.hpp"
#include "sha1.hpp"

Sha1::Sha1(void)
{
	b64hashed = "No value!";
	memset(binhashed, 0, sizeof binhashed);
}

Sha1::Sha1(const string& data)
{
	/* Hash it */
	hash_state md;
	sha1_init(&md);
	int rc = sha1_process(&md, reinterpret_cast<const uchar_t*>(data.c_str()),
		data.size());
	assert(rc == CRYPT_OK);
	rc = sha1_done(&md, binhashed);
	assert(rc == CRYPT_OK);
	b64();
}

/*
 * Initialises the Sha1 object from a binary hash
 */
Sha1::Sha1(const uchar_t binary[SHA1BIN_LEN])
{
	memcpy(binhashed, binary, sizeof binhashed);
	b64();
}

/*
 * Base 64 the binary hash
 */
void Sha1::b64(void)
{
	ulong_t outlen = 29;
	char tmp[outlen];
	// b64 FIXME: replace + with ~, and / with - to be like freenet
	int rc = base64_encode(binhashed, sizeof binhashed, reinterpret_cast<uchar_t*>(tmp), &outlen);
	assert(rc == CRYPT_OK);
	b64hashed = tmp;
}

/*
 * Compares two Sha1s, returning true if the this one is less than the right one
 */
bool Sha1::operator<(const Sha1& rhs) const
{
	Bigint lhsnum(binhashed, SHA1BIN_LEN);
	Bigint rhsnum(rhs.binhash(), SHA1BIN_LEN);
	if (lhsnum < rhsnum)
		return true;
	else
		return false;
}

/*
 * Assigns a value from another Sha1 to this one
 */
Sha1& Sha1::operator=(const Sha1& rhs)
{
	if (this != &rhs) {  // check for self-assignment: a = a
		b64hashed = rhs.b64hash();
		memcpy(binhashed, rhs.binhash(), sizeof binhashed);
	}
	return *this;
}

/*
 * Compares Sha1s for equality
 */
bool Sha1::operator==(const Sha1& rhs) const
{
	if (memcmp(binhashed, rhs.binhash(), sizeof binhashed) == 0)
		return true;
	else
		return false;
}

/*
 * Xors this Sha1 with another, and stores the result in a Bigint
 *
 * rhs - sha1 to xor this one with
 * result - will be filled with the result
 */
void Sha1::xor(const Sha1& rhs, Bigint& result) const
{
	Bigint lhsnum(binhashed, SHA1BIN_LEN);
	Bigint rhsnum(rhs.binhash(), SHA1BIN_LEN);
	lhsnum.xor(rhsnum, result);
}
