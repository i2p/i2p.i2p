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
#include "thread.hpp"

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
 * Checks whether the thread is running
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
 *
 * Returns: true if the thread was killed, or false if it was already dead
 */
bool Thread::kill(void)
{
	running_m.lock();
	if (!running) {
		running_m.unlock();
		return false;
	}
#ifdef WINTHREADS
	if (!TerminateThread(handle, 0)) {
		TCHAR str[80];
		throw Thread_error(win_strerror(str, sizeof str));
	}
#else
	int rc = pthread_cancel(id);
	if (!rc)
		throw Thread_error(strerror(rc));
#endif
	running = false;
	running_m.unlock();
	return true;
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
		throw Thread_error(win_strerror(str, sizeof str));
	}
#else
	int rc = pthread_create(&id, NULL, &the_thread, this);
	if (!rc) {
		continue_m.unlock();
		throw Thread_error(strerror(rc));
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
	Thread* t = dynamic_cast<Thread*>(param);
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
