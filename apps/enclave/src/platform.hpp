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

#ifndef PLATFORM_HPP
#define PLATFORM_HPP

/*
 * Operating system
 */
#define FREEBSD 0  // FreeBSD (untested)
#define MINGW   1  // Windows native (Mingw)
#define LINUX   2  // Linux
#define CYGWIN  3  // Cygwin

#if OS == MINGW
	#define NO_SSIZE_T
	#define WIN_STRERROR
	#define WINSOCK
	#define WINTHREADS
#endif

/*
 * System includes
 */
#include <arpa/inet.h>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <list>
#include <map>
#ifdef WINTHREADS
	#include <windows.h>
#else
	#include <pthread.h>
#endif
#include <stdexcept>
#include <stdint.h>
#include <string>
#include <time.h>

using namespace std;

#ifdef NO_SSIZE_T
	typedef signed long ssize_t;
#endif

/*
 * Define this to '1' to cause the printing of source code file and line number
 * information with each log message.  Set it to '0' for simple logging.
 */
#define VERBOSE_LOGS 0

/*
 * Library includes
 */
#include "mycrypt.h"  // LibTomCrypt
#include "sam.h"  // LibSAM

/*
 * Local includes
 */
#include "mutex.hpp"  // Mutex (for thread.hpp)
#include "thread.hpp"  // Thread
#include "logger.hpp"  // Logger
#include "config.hpp"  // Config
#include "sam_error.hpp"  // for sam.hpp
#include "bigint.hpp"  // for sha1.hpp
#include "sha1.hpp"  // for peers.hpp
#include "peer.hpp"  // for peers.hpp
#include "near_peer.hpp"  // for peers.hpp
#include "peers.hpp" // for sam.hpp
#include "sam.hpp"  // SAM
#include "random.hpp"  // Random

/*
 * Global variables
 */
extern Config *config;  // Configuration options
extern Logger *logger;  // Logging mechanism
extern Random *prng;  // Random number generator
extern Sam *sam;  // Sam connection

#endif  // PLATFORM_HPP
