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
#include "random.hpp"

/*
 * Prepares the Yarrow PRNG for use
 */
Random::Random(void)
{
	LMINOR << "Initalising PRNG\n";

	int rc = yarrow_start(&prng);
	assert(rc == CRYPT_OK);

	uchar_t entropy[ENTROPY_SIZE];
	size_t sz = rng_get_bytes(entropy, ENTROPY_SIZE, NULL);
	assert(sz == ENTROPY_SIZE);

	rc = yarrow_add_entropy(entropy, ENTROPY_SIZE, &prng);
	assert(rc == CRYPT_OK);

	rc = yarrow_ready(&prng);
	assert(rc == CRYPT_OK);
}

/*
 * Gets `size' random bytes from the PRNG
 *
 * random - space to fill with random bytes
 * size - size of `random'
 */
void Random::get_bytes(uchar_t* random, size_t size)
{
	size_t sz = yarrow_read(random, size, &prng);
	assert(sz == size);
}
