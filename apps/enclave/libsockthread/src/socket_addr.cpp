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
 * $Id$
 */

#include "platform.hpp"
#include "socket_error.hpp"
#include "socket_addr.hpp"
using namespace Libsockthread;

Socket_addr& Socket_addr::operator=(const Socket_addr& rhs)
{
	if (this == &rhs) // check for self-assignment: a = a
		return *this;

	if (rhs.domain == AF_INET) {
		ip = new char[INET_ADDRSTRLEN];
	else
		ip = new char[INET6_ADDRSTRLEN];
	domain = rhs.domain;
	host = rhs.host;
	strcpy(ip, rhs.ip);
	port = rhs.port;
	type = rhs.type;

	return *this;
}

void Socket_addr::resolve()
{
	hostent* hent = gethostbyname(host.c_str());
	if (hent == NULL)
		throw Socket_error(hstrerror(h_errno));
	assert(hent->h_addrtype == AF_INET || hent->h_addrtype == AF_INET6);
	domain = hent->h_addrtype;
	if (domain == AF_INET) {
		ip = new char[INET_ADDRSTRLEN];
	else
		ip = new char[INET6_ADDRSTRLEN];
	strcpy(ip, hent->h_addr_list[0]);
}
