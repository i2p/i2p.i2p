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

#include <ctime>
#include <string>
using namespace std;
#include "time.hpp"
using namespace Libsockthread;

/*
 * Converts the time to an ISO 8601 standard time and date and puts it in a string
 * Example: 2004-07-01 19:03:47Z
 */
string& Time::utc(string &s) const
{
	struct tm* tm;

	tm = gmtime(&unixtime);
	char t[21];
	strftime(t, sizeof t, "%Y-%m-%dT%H:%M:%SZ", tm);
	return s = t;
}

/*
 * Converts the time to an ISO 8601 standard date and puts it in a string
 * Example: 2004-07-01Z
 */
string& Time::utc_date(string &s) const
{
	struct tm* tm;

	tm = gmtime(&unixtime);
	char t[12];
	strftime(t, sizeof t, "%Y-%m-%dZ", tm);
	return s = t;
}

/*
 * Converts the time to an ISO 8601 standard time and puts it in a string
 * Example: 19:03:47Z
 */
string& Time::utc_time(string &s) const
{
	struct tm* tm;

	tm = gmtime(&unixtime);
	char t[10];
	strftime(t, sizeof t, "%H:%M:%SZ", tm);
	return s = t;
}

#ifdef UNIT_TEST
// g++ -Wall -DUNIT_TEST time.cpp -o time
#include <iostream>

int main(void)
{
	Time t;
	string s;
	cout << "Current date and time is " << t.utc(s) << '\n';
	cout << "Current date is " << t.utc_date(s) << '\n';
	cout << "Current time is " << t.utc_time(s) << '\n';

	return 0;
}
#endif  // UNIT_TEST
