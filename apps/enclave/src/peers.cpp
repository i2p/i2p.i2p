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
#include "near_peer.hpp"
#include "rpc.hpp"
#include "sha1.hpp"
#include "peers.hpp"

/*
 * Inform other peers of our existence and collect the destination addresses of
 * nearby peers
 */
void Peers::advertise_self(void)
{
	list<Near_peer> near_peers;
	get_nearest(sam->get_my_sha1(), PAR_RPCS, near_peers);
	for (near_peers_ci i = near_peers.begin(); i != near_peers.end(); i++) {
		Rpc rpc(i->get_peer());
		rpc.find_peers(sam->get_my_sha1());
	}
}

/*
 * Find the `n' nearest peers by xoring a sha1 with a kaddr
 *
 * sha1 - sha1 to find nearness to
 * n - number of peers to find
 * near_peers - a list to put the found peers in
 */
void Peers::get_nearest(const Sha1& sha1, size_t n,	list<Near_peer>& near_peers)
{
	near_peers.clear();  // prevents duplicate peers in the list
	for (peersmap_i i = peersmap.begin(); i != peersmap.end(); i++) {
		const Sha1& kaddr = i->first;
		Bigint distance;
		sha1.xor(kaddr, distance);
		Near_peer np(distance, &(i->second));
		near_peers.insert(near_peers.end(), np);
	}
	near_peers.sort();
	while (near_peers.size() > n)
		near_peers.pop_back();
}

Peer* Peers::get_peer_by_dest(const sam_pubkey_t dest)
{
	const string s = dest;
	return get_peer_by_dest(s);
}

/*
 * Gets a peer by its base 64 destination address
 *
 * dest - destination
 *
 * Returns: pointer to peer, or 0 if the peer wasn't found
 */
Peer* Peers::get_peer_by_dest(const string& dest)
{
	for (peersmap_i i = peersmap.begin(); i != peersmap.end(); i++) {
		Peer& tmp = i->second;
		if (tmp.get_dest() == dest)
			return &(i->second);
	}
	return 0;
}

/*
 * Gets a peer by its Kademlia address
 *
 * kaddr - Kademlia adddress
 *
 * Returns: pointer to peer, or 0 if the peer wasn't found
 */
Peer* Peers::get_peer_by_kaddr(const Sha1& kaddr)
{
	peersmap_i i = peersmap.find(kaddr);
	if (i != peersmap.end())
		return &(i->second);
	else
		return 0;
}

/*
 * Loads peer addresses from a file
 */
void Peers::load(void)
{
	string dest;

	ifstream peersf(file.c_str());
	if (!peersf) {
		LERROR << "Couldn't load peers reference file (" << file.c_str()
			<< ")\n";
		if (peersmap.size() > 0)
			return;
		else
			throw runtime_error("No peer references in memory");
	}

	for (getline(peersf, dest); peersf; getline(peersf, dest))
		new_peer(dest);

	if (peersmap.size() > 0) {
		LMINOR << peersmap.size() << " peer references in memory\n";
	} else
		throw runtime_error("No peer references in memory");
}

Peer* Peers::new_peer(const sam_pubkey_t dest)
{
	const string s = dest;
	return new_peer(s);
}

/*
 * Adds a newly discovered peer to the peers map
 *
 * dest - destination address of the peer
 *
 * Returns: pointer to the peer
 */
Peer* Peers::new_peer(const string& dest)
{
	// Check the destination address
	if (!sam->valid_dest(dest)) {
		LWARN << "Bad format in peer reference: " << dest.substr(0, 8) << '\n';
		return 0;
	}
	// Never add our own peer to the peers we can connect to
	if (dest == sam->get_my_dest()) {
		LDEBUG << "Not adding my own peer reference: " << dest.substr(0, 8)
			<< '\n';
		return 0;
	}
	// Be sure that the peer is not already known to us
	Peer *peer = get_peer_by_dest(dest);
	if (peer != 0) {
		LDEBUG << "Redundant peer reference: " << dest.substr(0, 8) << '\n';
		return peer;
	}

	// Tests passed, add it
	Sha1 sha1(dest);
	pair<peersmap_i, bool> p = peersmap.insert(
		make_pair(sha1, Peer(dest, sha1)));
	assert(p.second);
	LMINOR << "New peer reference: " << dest.substr(0, 8)
		<< " (Kaddr: " << sha1.b64hash() << ")\n";
	peer = &(p.first->second);
	return peer;
}

/*
 * Saves peer destinations to a file
 *
 * file - the file to save to
 */
void Peers::save(void)
{
	ofstream peersf(file.c_str());
	if (!peersf) {
		LERROR << "Error opening peers reference file (" << file.c_str()
			<< ")\n";
		return;
	}

	LDEBUG << "Saving " << peersmap.size() + 1 << " peer references\n";
	peersf << sam->get_my_dest() << '\n';
	for (peersmap_ci i = peersmap.begin(); i != peersmap.end(); i++) {
		const Peer& tmp = i->second;
		peersf << tmp.get_dest() << '\n';
	}
}

/*
 * Stores data on some peers
 *
 * sha1 - the sha1 value for the data
 * data - the data
 */
void Peers::store(const Sha1& sha1)
{
	list<Near_peer> near_peers;
	get_nearest(sam->get_my_sha1(), PAR_RPCS, near_peers);
	for (near_peers_ci i = near_peers.begin(); i != near_peers.end(); i++) {
		Rpc rpc(i->get_peer());
		rpc.store(sha1);
	}
}
