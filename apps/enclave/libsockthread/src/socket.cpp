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
#include "socket.hpp"

/*
 * Creates a socket
 *
 * type - either SOCK_STREAM or SOCK_DGRAM
 */
Socket::Socket(int type)
{
#ifdef WINSOCK
	winsock_startup();

}

#ifdef WINSOCK
/*
 * Unloads the Winsock network subsystem
 */
void Socket::winsock_cleanup(void)
{
	if (WSACleanup() == SOCKET_ERROR)
		throw Socket_error("WSACleanup() failed (" +
			winsock_strerror(WSAGetLastError()) + ")");  // TODO: log instead
}

/*
 * Loads the Winsock network sucksystem
 */
void Socket::winsock_startup(void)
{
	WORD wVersionRequested = MAKEWORD(2, 2);
	WSADATA wsaData;
	int rc = WSAStartup(wVersionRequested, &wsaData);
	if (rc != 0)
		throw Socket_error("WSAStartup() failed (" + winsock_strerror(rc) +")");
	if (LOBYTE(wsaData.wVersion) != 2 || HIBYTE(wsaData.wVersion) != 2) {
		winsock_cleanup();
		throw Socket_error("Bad Winsock version");
	}
}

/*
 * Apparently Winsock does not have a strerror() equivalent for its functions
 *
 * code - code from WSAGetLastError()
 *
 * Returns: error string (from http://msdn.microsoft.com/library/default.asp?
 *		url=/library/en-us/winsock/winsock/windows_sockets_error_codes_2.asp)
 */
const char* Socket::winsock_strerror(int code)
{
	switch (code) {
		case WSAEINTR:
			return "Interrupted function call";
		case WSAEACCES:  // yes, that is the correct spelling
			return "Permission denied";
		case WSAEFAULT:
			return "Bad address";
		case WSAEINVAL:
			return "Invalid argument";
		case WSAEMFILE:
			return "Too many open files";
		case WSAEWOULDBLOCK:
			return "Resource temporarily unavailable";
		case WSAEINPROGRESS:
			return "Operation now in progress";
		case WSAEALREADY:
			return "Operation already in progress";
		case WSAENOTSOCK:
			return "Socket operations on nonsocket";
		case WSAEDESTADDRREQ:
			return "Destination address required";
		case WSAEMSGSIZE:
			return "Message too long";
		case WSAEPROTOTYPE:
			return "Protocol wrong type for socket";
		case WSAENOPROTOOPT:
			return "Bad protocol option";
		case WSAEPROTONOSUPPORT:
			return "Protocol not supported";
		case WSAESOCKTNOSUPPORT:
			return "Socket type not supported";
		case WSAEOPNOTSUPP:
			return "Operation not supported";
		case WSAEPFNOSUPPORT:
			return "Protocol family not supported";
		case WSAEAFNOSUPPORT:
			return "Address family not supported by protocol family";
		case WSAEADDRINUSE:
			return "Address already in use";
		case WSAEADDRNOTAVAIL:
			return "Cannot assign requested address";
		case WSAENETDOWN:
			return "Network is down";
		case WSAENETUNREACH:
			return "Network is unreachable";
		case WSAENETRESET:
			return "Network dropped connection on reset";
		case WSAECONNABORTED:
			return "Software caused connection abort";
		case WSAECONNRESET:
			return "Connection reset by peer";
		case WSAENOBUFS:
			return "No buffer space available";
		case WSAEISCONN:
			return "Socket is already connected";
		case WSAENOTCONN:
			return "Socket is not connected";
		case WSAESHUTDOWN:
			return "Cannot send after socket shutdown";
		case WSAETIMEDOUT:
			return "Connection timed out";
		case WSAECONNREFUSED:
			return "Connection refused";
		case WSAEHOSTDOWN:
			return "Host is down";
		case WSAEHOSTUNREACH:
			return "No route to host";
		case WSAEPROCLIM:
			return "Too many processes";
		case WSASYSNOTREADY:
			return "Network subsystem is unavailable";
		case WSAVERNOTSUPPORTED:
			return "Winsock.dll version out of range";
		case WSANOTINITIALISED:
			return "Successful WSAStartup not yet performed";
		case WSAEDISCON:
			return "Graceful shutdown in progress";
		case WSATYPE_NOT_FOUND:
			return "Class type not found";
		case WSAHOST_NOT_FOUND:
			return "Host not found";
		case WSATRY_AGAIN:
			return "Nonauthoritative host not found";
		case WSANO_RECOVERY:
			return "This is a nonrecoverable error";
		case WSANO_DATA:
			return "Valid name, no data record of requested type";
/* None of this shit compiles under Mingw - who knows why...
		case WSA_INVALID_HANDLE:
			return "Specified event object handle is invalid";
		case WSA_INVALID_PARAMETER:
			return "One or more parameters are invalid";
		case WSA_IO_INCOMPLETE:
			return "Overlapped I/O event object not in signaled state";
		case WSA_IO_PENDING:
			return "Overlapped operations will complete later";
		case WSA_NOT_ENOUGH_MEMORY:
			return "Insufficient memory available";
		case WSA_OPERATION_ABORTED:
			return "Overlapped operation aborted";
		case WSAINVALIDPROCTABLE:
			return "Invalid procedure table from service provider";
		case WSAINVALIDPROVIDER:
			return "Invalid service provider version number";
		case WSAPROVIDERFAILEDINIT:
			return "Unable to initialize a service provider";
*/
		case WSASYSCALLFAILURE:
			return "System call failure";
		default:
			return "Unknown error";
	}
}
#endif  // WINSOCK
