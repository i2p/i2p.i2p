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

/*
 * Some helpful macros:
 *
 * LDEBUG - debugging messages
 * LMINOR - unimportant messages
 * LINFO - informational messages
 * LWARN - errors we automatically recover from
 * LERROR - major, important errors
 *
 * Obviously, these only work if your Logger object is called "logger" and is
 * global
 */
// Prints out the file name, function name, and line number before the message
#define LDEBUG(format, ...) logger.log(Logger::DEBUG, "%s:%s:%d:" \
	format, __FILE__, __func__, __LINE__, __VA_ARGS__)
// This is the same as above, except it doesn't accept varargs
#define LDEBUGS(str) logger.log(Logger::DEBUG, "%s:%s:%d:" \
	str, __FILE__, __func__, __LINE__);
#define LMINOR(format, ...) logger.log(Logger::MINOR, "%s:%s:%d:" \
	format, __FILE__, __func__, __LINE__, __VA_ARGS__)
#define LMINORS(str) logger.log(Logger::MINOR, "%s:%s:%d:" \
	str, __FILE__, __func__, __LINE__);
#define LINFO(format, ...) logger.log(Logger::INFO, "%s:%s:%d:" \
	format, __FILE__, __func__, __LINE__, __VA_ARGS__)
#define LINFOS(str) logger.log(Logger::INFO, "%s:%s:%d:" \
	str, __FILE__, __func__, __LINE__);
#define LWARN(format, ...) logger.log(Logger::WARN, "%s:%s:%d:" \
	format, __FILE__, __func__, __LINE__, __VA_ARGS__)
#define LWARNS(str) logger.log(Logger::WARN, "%s:%s:%d:" \
	str, __FILE__, __func__, __LINE__);
#define LERROR(format, ...) logger.log(Logger::ERROR, "%s:%s:%d:" \
	format, __FILE__, __func__, __LINE__, __VA_ARGS__)
#define LERRORS(str) logger.log(Logger::ERROR, "%s:%s:%d:" \
	str, __FILE__, __func__, __LINE__);

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
			priority_t get_loglevel(void)
				{ loglevel_m.lock(); priority_t ll = loglevel;
					loglevel_m.unlock(); return ll; }
			bool open(const string& file);
			void set_loglevel(priority_t priority)
				{ loglevel_m.lock(); loglevel = priority; loglevel_m.unlock(); }
		private:
			Mutex cerr_m;
			FILE* logf;
			Mutex logf_m;
			priority_t loglevel;
			Mutex loglevel_m;
	};
}

#endif  // LIBSOCKTHREAD_LOGGER_HPP
