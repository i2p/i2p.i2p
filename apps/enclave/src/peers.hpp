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

#ifndef PEERS_HPP
#define PEERS_HPP

class Peers {
	public:
		static const int PAR_RPCS = 3;  // The number of parallel RPCs to send
		static const int RET_REFS = 20;  // The number of peer refs to return on
										 // failed requests
		Peers(const string& file)
			: file(file)
			{ load(); }
		~Peers(void) { save(); }

		void advertise_self(void);
		void get_nearest(const Sha1& sha1, size_t n,
			list<Near_peer>& near_peers);
		Peer* get_peer_by_dest(const sam_pubkey_t dest);
		Peer* get_peer_by_dest(const string& dest);
		Peer* get_peer_by_kaddr(const Sha1& kaddr);
		Peer* new_peer(const sam_pubkey_t dest);
		Peer* new_peer(const string& dest);
		void store(const Sha1& sha1);

	private:
		typedef map<const Sha1, Peer>::const_iterator peersmap_ci;
		typedef map<const Sha1, Peer>::iterator peersmap_i;

		void load(void);
		void save(void);

		const string file;
		map<const Sha1, Peer> peersmap;
};

#endif  // PEERS_HPP
