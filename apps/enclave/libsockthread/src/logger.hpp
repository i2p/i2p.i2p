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

#ifndef LIBSOCKTHREAD_LOGGER_HPP
#define LIBSOCKTHREAD_LOGGER_HPP

namespace Libsockthread {
	class Logger {
		public:
			typedef enum {DEBUG = 0, MINOR = 1, INFO = 2, WARN = 3, ERROR = 4}
				priority_t;

			Logger(void)
				: logf(0), loglevel(Logger::DEBUG) { }
			~Logger(void) { close(); }

			void close(void);
			void log(priority_t priority, const char* format, ...);
			priority_t get_loglevel(void) const
				{ mutex.lock(); priority_t ll = loglevel; mutex.unlock(); return ll; }
			void open(const string& file);  // throws Logger_error
			void set_loglevel(priority_t priority)
				{ mutex.lock(); loglevel = priority; mutex.unlock(); }
		private:
			Mutex cerr_m;
			FILE* logf;
			priority_t loglevel;
			Mutex logger_m;
	};

	class Logger_error : public runtime_error {
		public:
			Logger_error(const string& s)
				: runtime_error(s) { }
	};
}

#endif  // LIBSOCKTHREAD_LOGGER_HPP
