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

#include <arpa/inet.h>
#include <cassert>
using namespace std;
#include "platform.hpp"
#include "socket.hpp"
#include "socket_addr.hpp"
using namespace Libsockthread;

Socket_addr::Socket_addr(int domain, const string& host, uint16_t port)
	: domain(domain), host(host), port(port)
{
	memset(&hostaddr, 0, sizeof hostaddr);
	hostaddr.sin_family = domain;
	hostaddr.sin_port = htons(port);
	resolve(host.c_str(), ipaddr);
	int rc;
#ifdef NO_INET_ATON
	rc = hostaddr.sin_addr.s_addr = inet_addr(ipaddr);
#elif defined NO_INET_PTON
	rc = inet_aton(ipaddr, &hostaddr.sin_addr);
#else
	rc = inet_pton(AF_INET, ipaddr, &hostaddr.sin_addr);
#endif
	assert(rc != 0 && rc != -1);
}

/*
 * Performs a DNS lookup on `hostname' and puts the result in `ipaddr'
 */
bool Socket::resolve(const char* hostname, char* ipaddr)
{
	struct hostent *h;
#ifdef NO_GETHOSTBYNAME2
	h = gethostbyname(hostname);
#else
	h = gethostbyname2(hostname, domain);
#endif
	if (h == 0) {
		LWARN("DNS resolution failed for %s", hostname);
		throw Socket_error("DNS resolution failed");
	}
	struct in_addr a;
	a.s_addr = ((struct in_addr *)h->h_addr)->s_addr;
#ifdef NO_INET_NTOP
	char *tmp;
	tmp = inet_ntoa(a);
	assert(tmp != 0);
	strlcpy(ipaddr, tmp, INET_ADDRSTRLEN);  // inet_ntoa() was very poorly designed
#else
	int rc = inet_ntop(domain, &a, ipaddr, INET_ADDRSTRLEN);
	assert(rc != 0);
#endif
}
