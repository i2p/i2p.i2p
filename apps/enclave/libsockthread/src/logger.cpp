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

#include <cstdarg>
#include <cstdio>
#include <iostream>
#include <string>
using namespace std;
#include "mutex.hpp"
#include "time.hpp"
#include "logger.hpp"
using namespace Libsockthread;

/*
 * Closes the log file
 */
void Logger::close(void)
{
	logger_m.lock();
	if (logf == 0) {
		logger_m.unlock();
		return;
	}
	if (fclose(logf) == EOF) {
		cerr_m.lock();
		cerr << "fclose() failed: " << strerror(errno) << '\n';
		cerr_m.unlock();
	}
	logf = 0;
	logger_m.unlock();
}

/*
 * Sends a line to the log file.  Uses variable arguments just like printf().
 */
void Logger::log(priority_t priority, const char* format, ...)
{
	if (priority < get_loglevel())
		return;

	char ll;
	switch (priority) {
		case Logger::DEBUG:
			ll = 'D';
			break;
		case Logger::MINOR:
			ll = 'M';
			break;
		case Logger::INFO:
			ll = 'I';
			break;
		case Logger::WARN:
			ll = 'W';
			break;
		case Logger::ERROR:
			ll = 'E';
			break;
		default:
			ll = '?';
	}

	va_list ap;
	va_start(ap, format);
	string s;
	Time t;
	logger_m.lock();

	if (logf != 0) {
		/*
		 * Remember!  If you change the format here, change it in the else too
		 */
		fprintf(logf, "%c %s ", ll, t.utc(s).c_str());
		vfprintf(logf, format, ap);
		fputc('\n', logf);
		if (fflush(logf) == EOF) {
			cerr_m.lock();
			cerr << "fflush() failed: " << strerror(errno) << '\n';
			cerr_m.unlock();
		}
	} else {
		// if they don't have an open log file, just use stderr
		fprintf(stderr, "%c %s ", ll, t.utc(s).c_str());
		vfprintf(stderr, format, ap);
		fputc('\n', stderr);
	}

	va_end(ap);
	logger_m.unlock();

	return;
}

/*
 * Opens a log file for appending.  If there already is an open log file, then
 * it is closed and the new one is opened.
 *
 * file - file location to open
 */
bool Logger::open(const string& file)
{
	close();
	logger_m.lock();
	logf = fopen(file.c_str(), "a");
	logger_m.unlock();
	if (logf != NULL) {
		return true;
	} else {
		cerr_m.lock();
		cerr << "fopen() failed (" << file << "): " << strerror(errno) << '\n';
		cerr_m.unlock();
		return false;
	}
}

#ifdef UNIT_TEST
// g++ -Wall -c thread.cpp -o thread.o
// g++ -Wall -c mutex.cpp -o mutex.o
// g++ -Wall -c time.cpp -o time.o
// g++ -Wall -DUNIT_TEST -c logger.cpp -o logger.o
// g++ -Wall -DUNIT_TEST logger.o mutex.o thread.o time.o -o logger -lpthread
int main(void)
{
	Logger logger;

	logger.open("delete.me");
	logger.set_loglevel(Logger::MINOR);
	logger.close();
	LWARNS("This should appear on stderr");
	logger.open("delete.me.also");
	LINFO("%s\n", "hey it works");
	LDEBUGS("This shouldn't be saved in the file.");

	return 0;
}
#endif  // UNIT_TEST
