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

#ifndef LOGGER_HPP
#define LOGGER_HPP

/*
 * LDEBUG - debugging messages
 * LMINOR - unimportant messages
 * LINFO - informational messages
 * LWARN - errors we automatically recover from
 * LERROR - major, important errors
 */
#if VERBOSE_LOGS
	#define LDEBUG logger.set_pri(Logger::debug); logger << "(D)" << __FILE__ << ':' << __LINE__ << ':' << __func__ << ": "
	#define LMINOR logger.set_pri(Logger::minor); logger << "(M)" << __FILE__ << ':' << __LINE__ << ':' << __func__ << ": "
	#define LINFO  logger.set_pri(Logger::info);  logger << "(I)" << __FILE__ << ':' << __LINE__ << ':' << __func__ << ": "
	#define LWARN  logger.set_pri(Logger::warn);  logger << "(W)" << __FILE__ << ':' << __LINE__ << ':' << __func__ << ": "
	#define LERROR logger.set_pri(Logger::error); logger << "(E)" << __FILE__ << ':' << __LINE__ << ':' << __func__ << ": "
#else
	#define LDEBUG logger.set_pri(Logger::debug); logger << "(D)"
	#define LMINOR logger.set_pri(Logger::minor); logger << "(M)"
	#define LINFO  logger.set_pri(Logger::info);  logger << "(I)"
	#define LWARN  logger.set_pri(Logger::warn);  logger << "(W)"
	#define LERROR logger.set_pri(Logger::error); logger << "(E)"
#endif

class Logger {
	public:
		typedef enum {debug = 0, minor = 1, info = 2, warn = 3, error = 4}
			priority_t;

		Logger(const string& file);

		void flush(void) { logf.flush(); }
		priority_t get_loglevel(void) const { return loglevel; }
		void set_loglevel(priority_t priority) { loglevel = priority; }
		Logger& operator<<(char c)
			{ if (priority >= loglevel) { logf << c; flush(); } return *this; }
		Logger& operator<<(const char* c)
			{ if (priority >= loglevel) { logf << c; flush(); } return *this; }
		Logger& operator<<(int i)
			{ if (priority >= loglevel) { logf << i; flush(); } return *this; }
		Logger& operator<<(const string& s)
			{ if (priority >= loglevel) { logf << s; flush(); } return *this; }
		Logger& operator<<(unsigned int i)
			{ if (priority >= loglevel) { logf << i; flush(); } return *this; }
		void set_pri(priority_t priority) { this->priority = priority; }

	private:
		priority_t priority;  // importance of the following log message(s)
		string file;
		priority_t loglevel;  // write log messsages at or above this priority
		ofstream logf;
};

#endif  // LOGGER_HPP
