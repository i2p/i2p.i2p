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

#include "platform.hpp"
#include "main.hpp"

Config *config;  // Configuration options
Logger *logger;  // Logging mechanism
Random *prng;  // Random number generator
Sam *sam;  // SAM connection

int main(int argc, char* argv[])
{
	if (argc != 2) {  // put some getopts stuff in here later
		cerr << "Please specify the configuration file location.\n" \
			"e.g. 'bin/enclave cfg/enclave.cfg'\n";
		return 1;
	}

	try {
		config = new Config(argv[1]);
	} catch (const runtime_error& x) {
		return 0;
	}
	logger = new Logger(config->get_cproperty("logfile"));
	LINFO << "Enclave DHT - Built on " << __DATE__ << ' ' << __TIME__ << '\n';
	prng = new Random;
	try {
		sam = new Sam(config->get_cproperty("samhost"),
			config->get_iproperty("samport"), config->get_cproperty("samname"),
			config->get_iproperty("tunneldepth"));
	} catch (const Sam_error& x) {
		LERROR << "SAM error: " << x.what() << '\n';
		cerr << "SAM error: " << x.what() << '\n';
		if (x.code() == SAM_SOCKET_ERROR) {
			LERROR << "Check whether you have specified the correct SAM host " \
				"and port number, and that I2P is running.\n";
			cerr << "Check whether you have specified the correct SAM host " \
				"and port number, and that\nI2P is running.\n";
		}
		return 1;
	}
	sam->naming_lookup();
	while (sam->get_my_dest() == "")
		sam->read_buffer();  // wait until we get our own dest back from lookup

	sam->peers->advertise_self();

	while (true)
		sam->read_buffer();

	delete sam;
	delete prng;
	delete logger;
	delete config;
	return 0;
}
