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
#include "bigint.hpp"

/******************************************************************************/
// Note: All the const_casts below are necessary because libtomcrypt doesn't  //
// have its arguments as const, even when they are not changed                //
/******************************************************************************/

Bigint::Bigint(const Bigint& bigint)
{
	init();
	copyover_mp_int(bigint.mpi);
}

Bigint::Bigint(const uchar_t* data, size_t size)
{
	init();
	import_uraw(data, size);
}

Bigint::Bigint(uint16_t i)
{
	init();
	i = htons(i);
	import_uraw(reinterpret_cast<uchar_t*>(&i), 2);
}

Bigint::Bigint(uint32_t i)
{
	init();
	i = htonl(i);
	import_uraw(reinterpret_cast<uchar_t*>(&i), 4);
}

/*
 * Replaces our current mp_int with another one
 * (just a wrapper for mp_copy)
 */
void Bigint::copyover_mp_int(const mp_int& i)
{
	int rc = mp_copy(const_cast<mp_int*>(&i), &mpi);
	assert(rc == MP_OKAY);
}

/*
 * Saves a Bigint to a raw unsigned big-endian integer
 * Note that the result must be freed with delete[]
 *
 * size - filled with the size of the output
 *
 * Returns: binary data
 */
uchar_t* Bigint::export_uraw(size_t& size) const
{
	uchar_t* out;
	size = mp_unsigned_bin_size(const_cast<mp_int*>(&mpi));
	if (size != 0) {
		out = new uchar_t[size];
		int rc = mp_to_unsigned_bin(const_cast<mp_int*>(&mpi), out);
		assert(rc == MP_OKAY);
	} else {  // size == 0
		size = 1;
		out = new uchar_t[1];
		out[0] = 0;
	}
	return out;
}

/*
 * Loads a raw unsigned big-endian integer into Bigint
 *
 * data - binary data
 * size - size of data
 */
void Bigint::import_uraw(const uchar_t* data, size_t size)
{
	uchar_t tmp[size];				// mp_read_unsigned_bin() arg 2 is not const
	memcpy(tmp, data, sizeof tmp);	// I'm not taking any chances
	int rc = mp_read_unsigned_bin(&mpi, tmp, sizeof tmp);
	assert(rc == MP_OKAY);
}

/*
 * Initialises the object
 */
void Bigint::init(void)
{
	int rc = mp_init(&mpi);
	assert(rc == MP_OKAY);
}

bool Bigint::operator<(const Bigint& rhs) const
{
	int rc = mp_cmp(const_cast<mp_int*>(&mpi), const_cast<mp_int*>(&rhs.mpi));
	if (rc == MP_LT)
		return true;
	else
		return false;
}

Bigint& Bigint::operator=(const Bigint& rhs)
{
	if (this != &rhs)  // check for self-assignment: a = a
		copyover_mp_int(rhs.mpi);
	return *this;
}

bool Bigint::operator==(const Bigint& rhs) const
{
	int rc = mp_cmp(const_cast<mp_int*>(&mpi), const_cast<mp_int*>(&rhs.mpi));
	if (rc == MP_EQ)
		return true;
	else
		return false;
}

bool Bigint::operator>(const Bigint& rhs) const
{
	int rc = mp_cmp(const_cast<mp_int*>(&mpi), const_cast<mp_int*>(&rhs.mpi));
	if (rc == MP_GT)
		return true;
	else
		return false;
}

/*
 * Xors another Bigint with this Bigint and puts the result in Bigint `result'
 *
 * rhs - the bigint to xor with
 * result - will be filled with the result of the xor
 */
void Bigint::xor(const Bigint& rhs, Bigint& result) const
{
	int rc = mp_xor(const_cast<mp_int*>(&mpi), const_cast<mp_int*>(&rhs.mpi),
		&result.mpi);
	assert(rc == MP_OKAY);
}
