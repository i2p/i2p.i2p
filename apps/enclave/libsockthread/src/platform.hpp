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
 * $Id: platform.hpp,v 1.5 2004/07/22 03:54:01 mpc Exp $
 */

/*
 * Global includes and platform configuration.  This is used to compile the
 * library, but is not intended for use by users of the library in their
 * own programs.
 */

#ifndef LIBSOCKTHREAD_PLATFORM_HPP
#define LIBSOCKTHREAD_PLATFORM_HPP

/*
 * Operating system
 */
#define FREEBSD	0  // FreeBSD
#define WIN32	1  // Windows
#define LINUX	2  // Linux

#if OS == WIN32
	#define WINSOCK
	#define WINTHREAD
#endif

#ifndef WINSOCK
	#include <arpa/inet.h>
#endif
#include <cassert>
#include <cstdarg>
#include <cstddef>
#include <cstdio>
#include <ctime>
#include <iostream>
#ifndef WINSOCK
	#include <netdb.h>
#endif
#ifndef WINTHREAD
	#include <pthread.h>
#endif
#include <stdint.h>  // TODO replace with Boost's version
#include <string>
#if defined WINSOCK || defined WINTHREAD
	#include <windows.h>
#endif
using namespace std;
#include "types.hpp"

#endif  // LIBSOCKTHREAD_PLATFORM_HPP
