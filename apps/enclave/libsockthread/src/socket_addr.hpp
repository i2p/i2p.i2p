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
 * $Id: socket_addr.hpp,v 1.3 2004/07/22 03:54:01 mpc Exp $
 */

#ifndef LIBSOCKTHREAD_SOCKET_ADDR_HPP
#define LIBSOCKTHREAD_SOCKET_ADDR_HPP

namespace Libsockthread {
	class Socket_addr {
		public:
			Socket_addr(Socket_addr& rhs);
			Socket_addr(int domain, int type, string& host, uint16_t port)
				: domain(domain), host(host), type(type), port(port)
				{ resolve(); }  // throws Socket_error
			~Socket_addr()
				{ delete[] ip; }

			int get_domain() const  // Has nothing to do with DNS domain -
				{ return domain; }  // returns either AF_INET or AF_INET6
			const char* get_ip() const  // Warning!  This can be NULL!
				{ return ip; }
			uint16_t get_port() const
				{ return port; }
			int get_type() const
				{ return type;
			Socket_addr& operator=(const Socket_addr& rhs);
			bool operator==(const Socket_addr& rhs);
			void set_domain(int domain)
				{ this->domain = domain; }
			void set_host(string& host)  // throws Socket_error
				{ this->host = host; resolve(); }
			void set_port(uint16_t port)
				{ this->port = port; }
			void set_type(int type)
				{ this->type = type; }
		private:
			void resolve();  // throws Socket_error

			int domain;
			string host;
			char* ip;
			uint16_t port;
			int type;
	};
}

#endif  // LIBSOCKTHREAD_SOCKET_ADDR_HPP
