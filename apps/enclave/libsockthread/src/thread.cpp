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
 *
 * $Id$
 */

/*
 * Modelled after JThread by Jori Liesenborgs
 */

#include "platform.hpp"
#include "mutex.hpp"
#include "thread.hpp"
using namespace Libsockthread;

/*
 * Gets the return value of a finished thread
 */
void* Thread::get_retval()
{
	void* val;
	running_m.lock();
	if (running)
		val = NULL;
	else
		val = retval;
	running_m.unlock();
	return val;
}

/*
 * Checks whether the thread is running
 */
bool Thread::is_running()
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
void Thread::kill()
{
	running_m.lock();
#ifndef NDEBUG
	// make sure it as actually running first
	if (!running) {
		running_m.unlock();
		assert(false);
	}
#endif
#ifdef WINTHREAD
	BOOL rc = TerminateThread(handle, NULL);
	assert(rc);
#else
	int rc = pthread_cancel(id);
	assert(rc == 0);
#endif
	running = false;
	running_m.unlock();
}

/*
 * Starts the thread
 */
void Thread::start()
{
#ifndef NDEBUG
	// check whether the thread is already running
	running_m.lock();
	assert(!running);
	running_m.unlock();
#endif
	continue_m.lock();
#ifdef WINTHREAD
	handle = CreateThread(NULL, 0, &the_thread, this, 0, &id);
	assert(handle != NULL);
#else
	int rc = pthread_create(&id, 0, &the_thread, this);
	assert(rc == 0);
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
	void* ret = t->thread();
	t->running_m.lock();
	t->running = false;
	t->retval = ret;
	t->running_m.unlock();
	return 0;
}

#ifdef UNIT_TEST
// g++ -Wall -c mutex.cpp -o mutex.o
// g++ -Wall -DUNIT_TEST -c thread.cpp -o thread.o
// g++ -Wall -DUNIT_TEST mutex.o thread.o -o thread -pthread
int main()
{
	class Thread_test : public Thread
	{
		public:
			Thread_test(int testval)
				: testval(testval) { }

			int get_testval()
			{
				testval_m.lock();
				int rc = testval;
				testval_m.unlock();
				return rc;
			}
			void *thread()
			{
				// just do something
				while (true) {
					testval_m.lock();
					++testval;
					testval_m.unlock();
				}
				return 0;
			}

		private:
			int testval;
			Mutex testval_m;
	};

	Thread_test t1(1);
	t1.start();
	Thread_test t2(1000000);
	t2.start();
	Thread_test t3(-1000000);
	t3.start();
	while (true) {
		if (t1.is_running())
			cout << "t1 is running..." << t1.get_testval() << '\n';
		if (t2.is_running())
			cout << "t2 is running..." << t2.get_testval() << '\n';
		if (t3.is_running())
			cout << "t3 is running..." << t3.get_testval() << '\n';
	}

	return 0;
}
#endif  // UNIT_TEST
