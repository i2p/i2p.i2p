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

#ifndef SAM_H
#define SAM_H
#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

/*
 * Lengths
 */
/* The maximum length a SAM command can be */
#define SAM_CMD_LEN 128
/*The maximum size of a single datagram packet (-30 temporary bug fix for SAM)*/
#define SAM_DGRAM_PAYLOAD_MAX ((31 * 1024) - 30)
/* The longest log message */
#define SAM_LOGMSG_LEN 256
/* The longest `name' arg for the naming lookup callback */
#define SAM_NAME_LEN 256
/* The max size of a single stream packet */
#define SAM_STREAM_PAYLOAD_MAX (32 * 1024)
/* The length of a base 64 public key - it's actually 516, but +1 for '\0' */
#define SAM_PUBKEY_LEN 517
/* A public key SAM command's length */
#define SAM_PKCMD_LEN (SAM_PUBKEY_LEN + SAM_CMD_LEN)
/* The maximum length a SAM non-data reply can be */
#define SAM_REPLY_LEN 1024

/*
 * Shorten some standard variable types
 */
typedef signed char schar_t;
typedef unsigned char uchar_t;
typedef unsigned int uint_t;
typedef unsigned long ulong_t;
typedef unsigned short ushort_t;

typedef enum {SAM_STREAM, SAM_DGRAM, SAM_RAW} sam_conn_t;  /* SAM connection */

typedef char sam_pubkey_t[SAM_PUBKEY_LEN];  /* base 64 public key */

typedef struct {
	void *data;
	size_t size;
} sam_sendq_t;  /* sending queue to encourage large stream packet sizes */

typedef int_fast32_t sam_sid_t;  /* stream id number */

typedef struct {
	socket_t sock;  /* the socket used for communications with SAM */
	bool connected;  /* whether the socket is connected */
	sam_sid_t prev_id;  /* the last stream id number we used */
} sam_sess_t;  /* a SAM session */

typedef enum {  /* see sam_strerror() for detailed descriptions of these */
	/* error codes from SAM itself (SAM_OK is not an actual "error") */
	SAM_OK, SAM_CANT_REACH_PEER, SAM_DUPLICATED_DEST, SAM_I2P_ERROR,
	SAM_INVALID_KEY, SAM_KEY_NOT_FOUND, SAM_PEER_NOT_FOUND, SAM_TIMEOUT,
	SAM_UNKNOWN,
	/* error codes from libsam */
	SAM_BAD_VERSION, SAM_CALLBACKS_UNSET, SAM_SOCKET_ERROR, SAM_TOO_BIG
} samerr_t;

/*
 * Public functions
 */

/* SAM controls - sessions */
extern sam_sess_t *sam_session_init(sam_sess_t *session);
extern void		sam_session_free(sam_sess_t **session);
/* SAM controls - connection */
extern bool		sam_close(sam_sess_t *session);
extern samerr_t	sam_connect(sam_sess_t *session, const char *samhost,
					uint16_t samport, const char *destname, sam_conn_t style,
					uint_t tunneldepth);
/* SAM controls - utilities */
extern void		sam_naming_lookup(sam_sess_t *session, const char *name);
extern bool		sam_read_buffer(sam_sess_t *session);
extern const char *sam_strerror(samerr_t code);
/* SAM controls - callbacks */
extern void		(*sam_diedback)();
extern void		(*sam_logback)(char *str);
extern void		(*sam_namingback)(char *name, sam_pubkey_t pubkey,
					samerr_t result);

/* Stream commands */
extern void		sam_stream_close(sam_sess_t *session, sam_sid_t stream_id);
extern sam_sid_t sam_stream_connect(sam_sess_t *session,
					const sam_pubkey_t dest);
extern samerr_t	sam_stream_send(sam_sess_t *session, sam_sid_t stream_id,
					const void *data, size_t size);
/* Stream commands - callbacks */
extern void		(*sam_closeback)(sam_sid_t stream_id, samerr_t reason);
extern void		(*sam_connectback)(sam_sid_t stream_id, sam_pubkey_t dest);
extern void		(*sam_databack)(sam_sid_t stream_id, void *data, size_t size);
extern void		(*sam_statusback)(sam_sid_t stream_id, samerr_t result);

/* Stream send queue (experimental) */
extern void		sam_sendq_add(sam_sess_t *session, sam_sid_t stream_id,
					sam_sendq_t **sendq, const void *data, size_t dsize);
extern void		sam_sendq_flush(sam_sess_t *session, sam_sid_t stream_id,
					sam_sendq_t **sendq);

/* Datagram commands */
extern samerr_t	sam_dgram_send(sam_sess_t *session, const sam_pubkey_t dest,
					const void *data, size_t size);

/* Datagram commands - callbacks */
extern void		(*sam_dgramback)(sam_pubkey_t dest, void *data, size_t size);

#ifdef __cplusplus
}
#endif
#endif  /* SAM_H */
