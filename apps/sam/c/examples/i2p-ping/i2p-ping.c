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
 * I2P-Ping won't compile on Windows because Windows lacks getopt()
 */

/*
 * Exit values:
 *	0: Received at least one response from one dest, or help
 *	   message was successfully displayed
 *	1: Received no responses from any dest
 *	2: Naming lookup failed, or dest unspecified
 *	3: SAM error
 */

#include <getopt.h>  /* needed on Gentoo Linux */
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "sam.h"

static void usage();

static void closeback(sam_sess_t *session, sam_sid_t stream_id,
	samerr_t reason);
static void connectback(sam_sess_t *session, sam_sid_t stream_id,
	sam_pubkey_t dest);
static void databack(sam_sess_t *session, sam_sid_t stream_id, void *data,
	size_t size);
static void diedback(sam_sess_t *session);
static void logback(char *s);
static void namingback(sam_sess_t *session, char *name, sam_pubkey_t pubkey,
	samerr_t result);
static void statusback(sam_sess_t *session, sam_sid_t stream_id,
	samerr_t result);

bool gotdest = false;
sam_pubkey_t dest;
bool quiet = false;
samerr_t laststatus = SAM_NULL;
sam_sid_t laststream = 0;
bool mihi = false;
bool bell = false;

int main(int argc, char *argv[])
{
	int ch;
	int count = INT_MAX;  /* number of times to ping */
	int pongcount = -1;
	char *samhost = "localhost";
	unsigned short samport = 7656;

	while ((ch = getopt(argc, argv, "ac:h:mp:qv")) != -1) {
		switch (ch) {
			case 'a':  /* bell */
				bell = true;
				break;
			case 'c':  /* packet count */
				count = atoi(optarg);
				break;
			case 'h':  /* SAM host */
				samhost = optarg;
				break;
			case 'm':  /* I2Ping emulation mode */
				count = 3;
				mihi = true;
				quiet = true;
				break;
			case 'p':  /* SAM port */
				samport = atoi(optarg);
				break;
			case 'q':  /* quiet mode */
				quiet = true;
				break;
			case 'v':  /* version */
				puts("$Id: i2p-ping.c,v 1.6 2004/12/02 17:54:23 mpc Exp $");
				puts("Copyright (c) 2004, Matthew P. Cashdollar <mpc@innographx.com>");
				break;
			case '?':
			default:
				usage();
				return 0;
		}
	}
	argc -= optind;
	argv += optind;
	if (argc == 0) {  /* they forgot to specify a ping target */
		fprintf(stderr, "Ping who?\n");
		return 2;
	}

	/* Hook up the callback functions - required by LibSAM */
	sam_closeback = &closeback;
	sam_connectback = &connectback;
	sam_databack = &databack;
	sam_diedback = &diedback;
	sam_logback = &logback;
	sam_namingback = &namingback;
	sam_statusback = &statusback;

	sam_sess_t *session = NULL;  /* set to NULL to have LibSAM do the malloc */
	session = sam_session_init(session);  /* malloc and set defaults */
	samerr_t rc = sam_connect(session, samhost, samport, "TRANSIENT",
		SAM_STREAM, 0);
	if (rc != SAM_OK) {
		fprintf(stderr, "SAM connection failed: %s\n", sam_strerror(rc));
		sam_session_free(&session);
		return 3;
	}

	pongcount = 0;
	for (int j = 0; j < argc; j++) {
		if (strlen(argv[j]) == SAM_PUBKEY_LEN - 1) {
			memcpy(dest, argv[j], SAM_PUBKEY_LEN);
			gotdest = true;
		} else
			sam_naming_lookup(session, argv[j]);

		while (!gotdest)  /* just wait for the naming lookup to complete */
			sam_read_buffer(session);
		gotdest = false;

		for (int i = 0; i < count; ++i) {
			time_t start = time(0);
			sam_sid_t sid = sam_stream_connect(session, dest);
			while (laststream != sid && laststatus == SAM_NULL)
				sam_read_buffer(session);  /* wait for the connect */
			if (laststatus == SAM_OK)
				sam_stream_close(session, laststream);
			time_t finish = time(0);
			laststream = 0;
			if (laststatus == SAM_OK) {
				pongcount++;

				if (bell)
					printf("\a");  /* putchar() doesn't work for some reason */
				if (!mihi)
					printf("%s: %.0fs\n", argv[j], difftime(finish, start));
				else
					printf("+ ");
			} else {
				if (!mihi)
					printf("%s: %s\n", argv[j], sam_strerror(laststatus));
				else
					printf("- ");
			}
			laststatus = SAM_NULL;
		}
		if (mihi)
			printf("  %s\n", argv[j]);
	}

	sam_close(session);
	sam_session_free(&session);
	return pongcount == 0 ? 1 : 0;
}

void usage()
{
	puts("usage: i2p-ping [-amqv?] [-c count] [-h samhost] [-p samport] " \
		"<b64dest|name>\n\t[b64dest|name] [b64dest|name] ...");
}

/*
 * Connection closed
 */
static void closeback(sam_sess_t *session, sam_sid_t stream_id, samerr_t reason)
{
	fprintf(stderr, "Connection closed to stream %d: %s\n", (int)stream_id,
		sam_strerror(reason));
}

/*
 * Someone connected to us - how dare they!
 */
static void connectback(sam_sess_t *session, sam_sid_t stream_id,
		sam_pubkey_t dest)
{
	sam_stream_close(session, stream_id);
}

/*
 * A peer sent us some data - just ignore it
 */
static void databack(sam_sess_t *session, sam_sid_t stream_id, void *data,
		size_t size)
{
	free(data);
}

/*
 * This is called whenever the SAM connection fails (like if the I2P router is
 * shut down)
 */
static void diedback(sam_sess_t *session)
{
	fprintf(stderr, "Lost SAM connection!\n");
	exit(3);
}

/*
 * The logging callback prints any logging messages from LibSAM (typically
 * errors)
 */
static void logback(char *s)
{
	if (!quiet)
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
		exit(2);
	}
	memcpy(dest, pubkey, SAM_PUBKEY_LEN);
	gotdest = true;
}

/*
 * Our connection attempt returned a result
 */
static void statusback(sam_sess_t *session, sam_sid_t stream_id,
	samerr_t result)
{
	laststatus = result;
	laststream = stream_id;
}
