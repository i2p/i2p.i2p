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
#include "chk.hpp"

Chk::Chk(const uchar_t* plaintext, size_t size, const string& mime_type)
	: data_size(size), mime_type(mime_type)
{
	encrypt(plaintext);
}

void Chk::encrypt(const uchar_t *pt)
{
	int rc = register_cipher(&twofish_desc);
	assert(rc != -1);

	uchar_t key[CRYPT_KEY_SIZE], iv[CRYPT_BLOCK_SIZE];
	prng.get_bytes(key, CRYPT_KEY_SIZE);
	prng.get_bytes(iv, CRYPT_BLOCK_SIZE);	

	symmetric_CTR ctr;
	rc = ctr_start(find_cipher("twofish"), iv, key, CRYPT_KEY_SIZE, 0, &ctr);
	assert(rc == CRYPT_OK);

	ct = new uchar_t[data_size];
	rc = ctr_encrypt(pt, ct, data_size, &ctr);
	assert(rc == CRYPT_OK);
}
