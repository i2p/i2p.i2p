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

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "sam.h"

static void dgramback(sam_pubkey_t dest, void *data, size_t size);
static void diedback(void);
static void logback(char *s);
static void namingback(char *name, sam_pubkey_t pubkey, samerr_t result);

/*
 * This is an extremely simple echo client which shows you how LibSAM
 * datagrams work.  We lookup the name 'dgram-server' then send some data to
 * him.  If everything works, we should get the same data back.
 */
int main(int argc, char* argv[])
{
	samerr_t rc;
	char errstr[SAM_ERRMSG_LEN];

	/* Hook up the callback functions */
	sam_dgramback = &dgramback;
	sam_diedback = &diedback;
	sam_logback = &logback;
	sam_namingback = &namingback;

	/* Connect to the SAM server */
	rc = sam_connect("localhost", 7656, "dgram-client", SAM_DGRAM, 0);
	if (rc != SAM_OK) {
		fprintf(stderr, "SAM connection failed: %s\n", sam_strerror(rc));
		exit(1);
	}
	/*
	 * This is equivalent to doing a DNS lookup on the normal internet.  Note
	 * that the dgram-server must already be running for this to work.
	 * When the lookup completes, we send them some data (see namingback()).
	 */
	sam_naming_lookup("dgram-server");

	/*
	 * Wait for something to happen, then invoke the appropriate callback
	 */
	while (true)
		sam_read_buffer();

	return 0;
}

/*
 * When we receive some data, print it out to the screen
 */
static void dgramback(sam_pubkey_t dest, void *data, size_t size)
{
	printf("Datagram received: %s\n", (char *)data);
	free(data);
}

/*
 * This is called whenever the SAM connection fails (like if the I2P router is
 * shut down)
 */
static void diedback(void)
{
	fprintf(stderr, "Lost SAM connection!\n");
	exit(1);
}

/*
 * The logging callback prints any logging messages from LibSAM
 */
static void logback(char *s)
{
	fprintf(stderr, "LibSAM: %s\n", s);
}

/*
 * When a name is resolved, send data to that destination address
 */
static void namingback(char *name, sam_pubkey_t pubkey, samerr_t result)
{
	char *data = "Hello, invisible world!";

	printf("I got %s's base 64 destination, so now I will send him some " \
		"data...\n", name);
	sam_dgram_send(pubkey, data, strlen(data));
	/*
	 * ^^^ An extra NUL is appended to the data by LibSAM, so it is not
	 * necessary to send trailing NULs over the wire for strings.  This doesn't
	 * cause problems with binary data, because the NUL isn't included in `size'
	 * in sam_dgramback().
	 * That is why we use strlen(data) instead of strlen(data) + 1.
	 */
	puts("Datagram sent");
}
