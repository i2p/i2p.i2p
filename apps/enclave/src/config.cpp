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
#include "bigint.hpp"

Config::Config(const string& file)
	: file(file)
{
	set_defaults();
	parse();
	configf.close();
}

void Config::parse(void)
{
	configf.open(file.c_str());
	if (!configf) {
		cerr << "Error opening configuration file (" << file.c_str() << ")\n";
		throw runtime_error("Error opening configuration file");
	}
	size_t line = 0;
	string s;
	for (getline(configf, s); configf; getline(configf, s)) {
		line++;
		if (s[0] == '#')  // comment
			continue;
		size_t eqpos = s.find("=");
		if (eqpos == string::npos) {
			LERROR << "Error parsing line #" << line << " in " << file << ": " << s << '\n';
			continue;
		}
		string key = s.substr(0, eqpos - 1);
		string value = s.substr(eqpos + 1);
		cfgmap.insert(make_pair(key, value));
	}
}

/*
 * Looks up a configuration option in the table and returns the value
 *
 * key - key to lookup
 *
 * Returns a pointer to the value associated with the key
 */
const string* Config::get_property(const string& key) const
{
	for (cfgmap_ci i = cfgmap.begin(); i != cfgmap.end(); i++) {
		string s = i->first;
		if (s == key)
			return &(i->second);
	}
	assert(false);
	return 0;
}

void Config::set_defaults(void)
{
	cfgmap.insert(make_pair("samhost", "localhost"));
	cfgmap.insert(make_pair("samport", "7656"));
	cfgmap.insert(make_pair("mydest", "enclave"));
	cfgmap.insert(make_pair("tunneldepth", "2"));
	cfgmap.insert(make_pair("references", "cfg/peers.ref"));
	cfgmap.insert(make_pair("loglevel", "1"));
	cfgmap.insert(make_pair("logfile", "log/enclave.log"));
}
