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
#include "rpc.hpp"

// These can't be 'const' because I have to make them big-endian first
uint16_t Rpc::VERSION = htons(1);
uint16_t Rpc::OLDEST_GOOD_VERSION = htons(1);

/*
 * Requests a peer to find the addresses of the closest peers to the specified
 * sha1 and return them
 *
 * sha1 - closeness to this sha1
 */
void Rpc::find_peers(const Sha1& sha1)
{
	LDEBUG << "To: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
		<< "] Msg: FIND_PEERS\n";

	// VERSION + command + bin sha1
	const size_t len = sizeof VERSION + 1 + Sha1::SHA1BIN_LEN;
	uchar_t buf[len];
	uchar_t* p = static_cast<uchar_t*>(memcpy(buf, &VERSION, sizeof VERSION));
	p += sizeof VERSION;
	*p = FIND_PEERS;
	p++;
	memcpy(p, sha1.binhash(), Sha1::SHA1BIN_LEN);
	sam->send_dgram(peer->get_dest(), buf, len);
}

/*
 * Returns the closest peer references to a Sha1
 *
 * sha1 - sha1 to test nearness to
 */
void Rpc::found_peers(const Sha1& sha1)
{
	list<Near_peer> near_peers;
	sam->peers->get_nearest(sha1, Peers::RET_REFS, near_peers);
	LDEBUG << "To: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
		<< "] Msg: FOUND_PEERS (" << near_peers.size() << " peers)\n";

	// VERSION + command + number of sha1s (0-255) + bin sha1s
	const size_t len = sizeof VERSION + 1 + 1 +
		(near_peers.size() * (SAM_PUBKEY_LEN - 1));
	assert(near_peers.size() <= 255);
	uchar_t buf[len];
	uchar_t* p = static_cast<uchar_t*>(memcpy(buf, &VERSION, sizeof VERSION));
	p += sizeof VERSION;
	*p = FOUND_PEERS;
	p++;
	*p = near_peers.size();
	p++;
	for (Peers::near_peers_ci i = near_peers.begin(); i != near_peers.end();
			i++) {
		const Peer* peer = i->get_peer();
		memcpy(p, peer->get_dest().c_str(), (SAM_PUBKEY_LEN - 1));
		p += SAM_PUBKEY_LEN - 1;
	}
	sam->send_dgram(peer->get_dest(), buf, len);
}

/*
 * Parse incoming data and invoke the appropriate RPC
 *
 * data - the data
 * size - the size of `data'
 */
void Rpc::parse(const void* data, size_t size)
{
	uint16_t his_ver;

	memcpy(&his_ver, data, sizeof VERSION);
	if (ntohs(his_ver) < ntohs(VERSION)) {
		LMINOR << "Ignored RPC from " << peer->get_sdest() << " ["
			<< peer->get_b64kaddr() << "] using obsolete protocol version "
			<< ntohs(his_ver) << '\n';
		return;
	} else if (size <= 4) {
		LWARN << "RPC too small from " << peer->get_sdest() << " ["
			<< peer->get_b64kaddr() << "]\n";
		return;
	}
	const uchar_t* p = static_cast<const uchar_t*>(data);

	if (p[2] == PING) {  //-----------------------------------------------------
		LDEBUG << "From: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
			<< "] Msg: PING\n";
		uint32_t ptime;
		if (size != sizeof VERSION + 1 + sizeof ptime) {
			LWARN << "Malformed PING RPC from " << peer->get_sdest()
				<< " [" << peer->get_b64kaddr() << "]\n";
			return;
		}
		p += sizeof VERSION + 1;
		memcpy(&ptime, p, sizeof ptime);
		pong(ptime); // no need to ntohl() it here because we're just copying it
		return;

	} else if (p[2] == PONG) {  //----------------------------------------------
		LDEBUG << "From: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
			<< "] Msg: PONG\n";
		uint32_t ptime;
		if (size != sizeof VERSION + 1 + sizeof ptime) {
			LWARN << "Malformed PONG RPC from " << peer->get_sdest()
				<< " [" << peer->get_b64kaddr() << "]\n";
			return;
		}
		p += sizeof VERSION + 1;
		memcpy(&ptime, p, sizeof ptime);
		ptime = ntohl(ptime);
		uint32_t now = time(NULL);
		peer->set_lag(now - ptime);
		LDEBUG << "Lag is " << peer->get_lag() << " seconds\n";
		return;

	} else if (p[2] == FIND_PEERS) {  //----------------------------------------
		if (size != sizeof VERSION + 1 + Sha1::SHA1BIN_LEN) {
			LWARN << "Malformed FIND_PEERS RPC from " << peer->get_sdest()
				<< " [" << peer->get_b64kaddr() << "]\n";
			return;
		}
		LDEBUG << "From: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
			<< "] Msg: FIND_PEERS\n";
		found_peers(Sha1(p + 4));
		return;

	} else if (p[2] == FOUND_PEERS) {  //---------------------------------------
		const size_t refs = p[3];
		if (size != sizeof VERSION + 1 + 1 + (refs * (SAM_PUBKEY_LEN - 1))) {
			LWARN << "Malformed FOUND_PEERS RPC from " << peer->get_sdest()
				<< " [" << peer->get_b64kaddr() << "]\n";
			return;
		}
		LDEBUG << "From: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
			<< "] Msg: FOUND_PEERS (" << refs << " peers)\n";
		p += sizeof VERSION + 1 + 1;
		for (size_t i = 1; i <= refs; i++) {
			sam_pubkey_t dest;
			memcpy(dest, p, SAM_PUBKEY_LEN - 1);  // - 1 == no NUL in RPC
			dest[SAM_PUBKEY_LEN - 1] = '\0';
			//LDEBUG << "Message had: " << dest << '\n';
			sam->peers->new_peer(dest);
			p += SAM_PUBKEY_LEN - 1;
		}
		return;

	} else  //------------------------------------------------------------------
		LWARN << "Unknown RPC #" << static_cast<int>(p[2]) << " from "
			<< peer->get_sdest() << " [" << peer->get_b64kaddr() << "]\n";
}

/*
 * Sends a ping to someone
 */
void Rpc::ping(void)
{
	LDEBUG << "To: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
		<< "] Msg: PING\n";

	uint32_t now = htonl(time(NULL));
	// VERSION + command + seconds since 1970
	const size_t len = sizeof VERSION + 1 + sizeof now;
	uchar_t buf[len];
	uchar_t* p = static_cast<uchar_t*>(memcpy(buf, &VERSION, sizeof VERSION));
	p += sizeof VERSION;
	*p = PING;
	p++;
	memcpy(p, &now, sizeof now);
	sam->send_dgram(peer->get_dest(), buf, len);
}

/*
 * Sends a ping reply to someone
 *
 * ptime - the time the peer sent us (we echo the same time back)
 */
void Rpc::pong(uint32_t ptime)
{
	LDEBUG << "To: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
		<< "] Msg: PONG\n";

	// VERSION + command + pinger's seconds since 1970 echoed back
	const size_t len = sizeof VERSION + 1 + sizeof ptime;
	uchar_t buf[len];
	uchar_t* p = static_cast<uchar_t*>(memcpy(buf, &VERSION, sizeof VERSION));
	p += sizeof VERSION;
	*p = PONG;
	p++;
	memcpy(p, &ptime, sizeof ptime);
	sam->send_dgram(peer->get_dest(), buf, len);
}

/*
 * Tells a peer to store some data
 *
 * sha1 - sha1 value for the data
 * data - the data
 */
void Rpc::store(const Sha1& sha1)
{
	LDEBUG << "To: " << peer->get_sdest() << " [" << peer->get_b64kaddr()
		<< "] Msg: STORE\n";
}
