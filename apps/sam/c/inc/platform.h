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

#ifndef LIBSAM_PLATFORM_H
#define LIBSAM_PLATFORM_H

/*
 * Operating system
 */
#define FREEBSD	0  // FreeBSD
#define CYGWIN	1  // Cygwin
#define LINUX	2  // Linux
#define MINGW	3  // Windows native (Mingw)
#define MSVC	4  // Windows native (Visual C++ 2003)

#if OS == CYGWIN
	#define INET_ADDRSTRLEN 16
	#define NO_GETHOSTBYNAME2
	#define NO_INET_NTOP
	#define NO_INET_PTON
	#define NO_SNPRINTF
	#define NO_STRL
	#define NO_VSNPRINTF
	#define NO_Z_FORMAT
#endif

#if OS == LINUX
	#define NO_GETHOSTBYNAME2
	#define NO_STRL
	#define NO_Z_FORMAT
#endif

#if OS == MINGW
	#define INET_ADDRSTRLEN 16
	#define NO_GETHOSTBYNAME2
	#define NO_INET_ATON  // implies NO_INET_PTON
	#define NO_INET_NTOP
	#define NO_SSIZE_T
	#define NO_STRL
	#define NO_Z_FORMAT
	#define WINSOCK
#endif

#if OS == MSVC  // FIXME: doesn't work
	#define NO_STDBOOL_H
	#define NO_SSIZE_T
	#define NO_STRL
	#define WINSOCK
#endif

/*
 * System includes
 */
#ifdef WINSOCK
	#include <winsock.h>
#else
	#include <arpa/inet.h>
	#include <netinet/in.h>
	#include <sys/select.h>
	#include <sys/socket.h>
	#include <sys/types.h>
#endif
#include <assert.h>
#include <errno.h>
#ifndef WINSOCK
	#include <netdb.h>
#endif
#if defined NO_SNPRINTF || defined NO_VSNPRINTF
	#include "snprintf.h"
#endif
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef NO_STRL
	#include "strl.h"
#endif
#ifdef WINSOCK
	#include <windows.h>
#else
	#include <unistd.h>
#endif

/*
 * Platform-dependent variable types
 */
#ifdef NO_SSIZE_T
	typedef signed long ssize_t;
#endif

/*
 * I'm too lazy to type "unsigned"
 */
typedef unsigned char byte;
typedef unsigned int uint;
typedef unsigned short ushort;

/*
 * Prints out the file name, line number, and function name before log message
 */
#define SAMLOG(format, ...) sam_log("%s:%d:%s: " \
	format, __FILE__, __LINE__, __func__, __VA_ARGS__)

/*
 * This is the same as above, except that it doesn't accept any va args
 */
#define SAMLOGS(str) sam_log("%s:%d:%s: " str, __FILE__, __LINE__, __func__)

/*
 * Set this to '1' if you want the raw SAM commands to be printed on stdout
 * (useful for debugging).  Otherwise, set it to '0'.
 */
#define SAM_WIRETAP 0
#if SAM_WIRETAP
	#include <ctype.h>
#endif

#endif  /* LIBSAM_PLATFORM_H */
