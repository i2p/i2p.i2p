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

// Based on JThread by Jori Liesenborgs (MIT License) and SAM Threads (public
// domain) by Nickster

// JThread license: (just in case this is necessary)
/*

    This file is a part of the JThread package, which contains some object-
    oriented thread wrappers for different thread implementations.

    Copyright (c) 2000-2001  Jori Liesenborgs (jori@lumumba.luc.ac.be)

    Permission is hereby granted, free of charge, to any person obtaining a
    copy of this software and associated documentation files (the "Software"),
    to deal in the Software without restriction, including without limitation
    the rights to use, copy, modify, merge, publish, distribute, sublicense,
    and/or sell copies of the Software, and to permit persons to whom the
    Software is furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in
    all copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
    THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
    FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
    DEALINGS IN THE SOFTWARE.

*/

#include "platform.hpp"
#include "mutex.hpp"
#include "thread.hpp"

Thread::Thread(void)
{
	retval = 0;
	running = false;
}

Thread::~Thread(void)
{
	kill();
}

/*
 * Gets the return value of a finished thread
 */
void* Thread::get_retval(void)
{
	void* val;
	running_m.lock();
	if (running)
		val = 0;
	else
		val = retval;
	running_m.unlock();
	return val;
}

/*
 * Checks if the thread is running
 *
 * Returns: true if it is running, false if it isn't
 */
bool Thread::is_running(void)
{
	running_m.lock();
	bool r = running;
	running_m.unlock();
	return r;
}

/*
 * Stops the thread
 * Generally NOT a good idea
 */
void Thread::kill(void)
{
	running_m.lock();
	assert(running);
#ifdef WINTHREADS
	if (!TerminateThread(handle, 0)) {
		TCHAR str[80];
		LERROR << win_strerror(str, sizeof str) << '\n';
		throw runtime_error(str);
	}
#else
	int rc = pthread_cancel(id);
	if (rc != 0) {
		LERROR << strerror(rc);
		throw runtime_error(strerror(rc));
	}
#endif
	running = false;
	running_m.unlock();
}

/*
 * Starts the thread
 */
void Thread::start(void)
{
	#ifndef NDEBUG
		// check whether the thread is already running
		running_m.lock();
		assert(!running);
		running_m.unlock();
	#endif
	continue_m.lock();
#ifdef WINTHREADS
	handle = CreateThread(NULL, 0, &the_thread, this, 0, &id);
	if (handle == NULL) {
		TCHAR str[80];
		LERROR << win_strerror(str, sizeof str) << '\n';
		throw runtime_error(str);
	}
#else
	int rc = pthread_create(&id, NULL, &the_thread, this);
	if (rc != 0) {
		continue_m.unlock();
		LERROR << strerror(rc) << '\n';
		throw runtime_error(strerror(rc));
	}
#endif
	// Wait until `running' is set
	running_m.lock();
	while (!running) {
		running_m.unlock();
		running_m.lock();
	}
	running_m.unlock();
	continue_m.unlock();
}

/*
 * Wrapper for the thread
 */
void* Thread::the_thread(void *param)
{
	Thread* t = static_cast<Thread*>(param);
	t->running_m.lock();
	t->running = true;
	t->running_m.unlock();
	// wait until we can continue
	t->continue_m.lock();
	t->continue_m.unlock();
	void* ret = t->execute();
	t->running_m.lock();
	t->running = false;
	t->retval = ret;
	t->running_m.unlock();
	return 0;
}
