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
#include "sam.hpp"

extern "C" {
	/*
	 * Assorted callbacks required by LibSAM - ugly, but it works
	 */
	static void dgramback(sam_pubkey_t dest, void* data, size_t size);
	static void diedback(void);
	static void logback(char* str);
	static void namingback(char* name, sam_pubkey_t pubkey, samerr_t result);
}

/*
 * Prevents more than one Sam object from existing in the program at a time
 * (LibSAM limitation)
 */
bool Sam::exists = false;

Sam::Sam(const string& samhost, uint16_t samport, const string& destname,
	uint_t tunneldepth)
{
	// Only allow one Sam object to exist at a time
	assert(!exists);
	exists = true;

	// hook up callbacks
	sam_dgramback = &dgramback;
	sam_diedback = &diedback;
	sam_logback = &logback;
	sam_namingback = &namingback;

	// we haven't connected to SAM yet
	set_connected(false);

	// now try to connect to SAM
	connect(samhost.c_str(), samport, destname.c_str(), tunneldepth);
}

Sam::~Sam(void)
{
	delete peers;  // this must be before set_connected(false)!
	if (get_connected()) {
		sam_close();
		set_connected(false);
	}
	exists = false;
}

/*
 * Connects to the SAM host
 *
 * samhost - host that SAM is running on (hostname or IP address)
 * samport - port number that SAM is running own
 * destname - the destination name of this program
 * tunneldepth - how long the tunnels should be
 */
void Sam::connect(const char* samhost, uint16_t samport, const char* destname,
	uint_t tunneldepth)
{
	assert(!get_connected());
	LMINOR << "Connecting to SAM as '" << destname << "'\n";
	samerr_t rc = sam_connect(samhost, samport, destname, SAM_DGRAM, tunneldepth);
	if (rc == SAM_OK)
		set_connected(true);
	else
		throw Sam_error(rc);
}

/*
 * Loads peer references from disk
 * Note: this can only be called after my_dest has been set
 */
void Sam::load_peers(void)
{
	peers = new Peers(config->get_cproperty("references"));
}

/*
 * Converts `name' to a base 64 destination
 *
 * name - name to lookup
 */
void Sam::naming_lookup(const string& name) const
{
	assert(get_connected());
	sam_naming_lookup(name.c_str());
}

/*
 * Parses an incoming datagram
 *
 * dest - source destination address
 * data - datagram payload
 * size - size of `data'
 */
void Sam::parse_dgram(const string& dest, void* data, size_t size)
{
	assert(get_connected());
	Peer* peer = peers->new_peer(dest);
	Rpc rpc(peer);
	rpc.parse(data, size);
	rpc.ping();
	free(data);
}

/*
 * Checks the SAM connection for incoming commands and invokes callbacks
 */
void Sam::read_buffer(void)
{
	assert(get_connected());
	sam_read_buffer();
}

/*
 * Sends a datagram to a destination
 *
 * dest - destination to send to
 * data - data to send
 * size - size of `data'
 */
void Sam::send_dgram(const string& dest, uchar_t *data, size_t size)
{
	samerr_t rc = sam_dgram_send(dest.c_str(), data, size);
	assert(rc == SAM_OK);  // i.e. not SAM_TOO_BIG
}

/*
 * Sets the connection status
 *
 * connected - true for connected, false for disconnected
 */
void Sam::set_connected(bool connected)
{
	if (!connected)
		my_dest = "";
	this->connected = connected;
}

/*
 * Sets my destination address
 *
 * pubkey - the base 64 destination
 */
void Sam::set_my_dest(const sam_pubkey_t pubkey)
{
	my_dest = pubkey;
	my_sha1 = Sha1(my_dest);
}

/*
 * Checks whether the destination specified is of a valid base 64 syntax
 *
 * Returns: true if it is valid, false if it isn't
 */
bool Sam::valid_dest(const string& dest)
{
	if (dest.size() != 516)
		return false;
	if (dest.substr(512, 4) == "AAAA")  // note this AAAA signifies a null
		return true;					// certificate - may not be true in the
	else								// future
		return false;
}

/*
 * * * * Callbacks * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Unfortunately these aren't part of the "Sam" object because they are function
 * pointers to _C_ functions.  As a hack, we just have them call the global Sam
 * object.
 */

/*
 * Callback: A datagram was received
 */
static void dgramback(sam_pubkey_t dest, void* data, size_t size)
{
	sam->parse_dgram(dest, data, size);
}

/*
 * Callback: The connection to SAM has failed
 */
static void diedback(void)
{
	LERROR << "Connection to SAM lost!\n";
	sam->set_connected(false);
	throw Sam_error(SAM_SOCKET_ERROR);
}

/*
 * Callback: A log message has been sent from LibSAM
 */
static void logback(char* str)
{
	LINFO << "LibSAM: " << str << '\n';
}

/*
 * Callback: A naming lookup has completed
 */
static void namingback(char* name, sam_pubkey_t pubkey, samerr_t result)
{
	Sam_error res(result);
	if (res.code() == SAM_OK) {
		if (strcmp(name, "ME") == 0) {
			sam->set_my_dest(pubkey);
			sam->load_peers();
		} else {
			assert(false);
		}
	} else {
		LERROR << "Naming look failed for '" << name << "': " << res.what()
			<< '\n';
	}
}
