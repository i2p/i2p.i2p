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

#ifndef THREAD_HPP
#define THREAD_HPP

namespace Libsockthread {

	class Thread {
	public:
		Thread(void)  // throws Mutex_error (a mutex is created)
			: retval(0), running(false) { }
		virtual ~Thread(void)
			{ kill(); }

		virtual void *execute(void) = 0;
		void* get_retval(void);
		bool is_running(void);
		bool kill(void);  // throws Thread_error
		void start(void);  // throws Thread_error

	private:
#ifdef WINTHREAD
		static DWORD WINAPI the_thread(void* param);
		HANDLE handle;
		DWORD id;
#else
		static void* the_thread(void* param);
		pthread_t id;
#endif
		void *retval;
		bool running;
		Mutex running_m;
		Mutex continue_m;
	};

	class Thread_error : public runtime_error {
		Thread_error(const string& s) : runtime_error(s) { }
	};

}

#endif  // THREAD_HPP
