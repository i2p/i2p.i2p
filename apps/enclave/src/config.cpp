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

/*
 * Looks up a configuration option in the table and returns a constant value.
 * This is the same as get_property() except the value returned is a constant.
 *
 * key - key to lookup
 *
 * Returns the value associated with the key
 */
const string& Config::get_cproperty(const string& key) const
{
	for (cfgmap_ci i = cfgmap.begin(); i != cfgmap.end(); i++) {
		const string s = i->first;
		if (s == key)
			return i->second;
	}
	LERROR << "Tried to lookup an invalid property: " << key << '\n';
	assert(false);
	// this should never occur, it's just to silence a compiler warning
	string* s = new string;
	return *s;
}

/*
 * Gets a property as an integer (they are all stored as strings)
 *
 * key - key to lookup
 *
 * Returns an integer of the value associated with the key
 */
int Config::get_iproperty(const string& key) const
{
	for (cfgmap_ci i = cfgmap.begin(); i != cfgmap.end(); i++) {
		const string s = i->first;
		if (s == key)
			return atoi(i->second.c_str());
	}
	LERROR << "Tried to lookup an invalid property: " << key << '\n';
	assert(false);
	return 0;
}

/*
 * Looks up a configuration option in the table and returns the value
 *
 * key - key to lookup
 *
 * Returns the value associated with the key
 */
string& Config::get_property(const string& key)
{
	for (cfgmap_i i = cfgmap.begin(); i != cfgmap.end(); i++) {
		const string s = i->first;
		if (s == key)
			return i->second;
	}
	LERROR << "Tried to lookup an invalid property: " << key << '\n';
	assert(false);
	// this should never occur, it's just to silence a compiler warning
	string* s = new string;
	return *s;
}

/*
 * Parses the configuration file, replacing default values with user defined
 * values
 */
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
		if (s.size() == 0 || s[0] == '#')  // blank line or comment
			continue;
		size_t eqpos = s.find("=");
		if (eqpos == string::npos) {
			cerr << "Error parsing line #" << line << " in " << file << ": "
				<< s << '\n';
			continue;
		}
		string key = s.substr(0, eqpos);
		string value = s.substr(eqpos + 1);
		//cout << "Inserting key = " << key << " value = " << value << '\n';
		cfgmap.erase(key);  // erase the default value created by set_defaults()
		cfgmap.insert(make_pair(key, value));
	}
}

/*
 * If you (the programmer) add something to the config file you should also add
 * it here, and vice versa
 */
void Config::set_defaults(void)
{
	cfgmap.insert(make_pair("samhost", "localhost"));
	cfgmap.insert(make_pair("samport", "7656"));
	cfgmap.insert(make_pair("samname", "enclave"));
	cfgmap.insert(make_pair("tunneldepth", "2"));
	cfgmap.insert(make_pair("references", "cfg/peers.ref"));
	cfgmap.insert(make_pair("loglevel", "1"));
	cfgmap.insert(make_pair("logfile", "log/enclave.log"));
}
