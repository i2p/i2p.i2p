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

// Modelled after JThread by Jori Liesenborgs

#include "platform.hpp"
#include "mutex.hpp"

/*
 * Creates a mutex
 */
Mutex::Mutex(void)
{
#ifdef WINTHREAD
	mutex = CreateMutex(NULL, FALSE, NULL);
	assert(mutex != NULL);
#else
	int rc = pthread_mutex_init(&mutex, NULL);
	assert(rc == 0);
#endif
}

/*
 * Destroys a mutex
 */
Mutex::~Mutex(void)
{
#ifdef WINTHREAD
	BOOL rc = CloseHandle(mutex);
	assert(rc);
#else
	int rc = pthread_mutex_destroy(&mutex);
	assert(rc == 0);
#endif
}

/*
 * Locks the mutex
 */
void Mutex::lock(void)
{
#ifdef WINTHREAD
	DWORD rc = WaitForSingleObject(mutex, INFINITE);
	assert(rc != WAIT_FAILED);
#else
	int rc = pthread_mutex_lock(&mutex);
	assert(rc == 0);
#endif
}

/*
 * Unlocks the mutex
 */
void Mutex::unlock(void)
{
#ifdef WINTHREAD
	BOOL rc = ReleaseMutex(mutex);
	assert(rc);
#else
	int rc = pthread_mutex_unlock(&mutex);
	assert(rc == 0);
#endif
}
