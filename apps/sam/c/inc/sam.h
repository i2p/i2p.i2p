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

/*
 * Lengths
 */
#define SAM_CMD_LEN 128  /* the maximum length a SAM command can be */
#define SAM_DGRAM_PAYLOAD_MAX 31774  /* max size of a single datagram packet */
#define SAM_ERRMSG_LEN 23  /* the longest message returned from sam_strerror */
#define SAM_LOGMSG_LEN 256	/* the longest log message */
#define SAM_NAME_LEN 256  /* the longest `name' arg for naming lookup callback*/
#define SAM_STREAM_PAYLOAD_MAX 32768  /* max size of a single stream packet */
#define SAM_PKCMD_LEN (SAM_PUBKEY_LEN + SAM_CMD_LEN)/*a public key SAM command*/
#define SAM_PUBKEY_LEN 517  /* it's actually 516, but +1 for '\0' */
#define SAM_REPLY_LEN 1024  /* the maximum length a SAM non-data reply can be */

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

/* SAM controls */
extern bool		sam_close(void);
extern samerr_t	sam_connect(const char *samhost, uint16_t samport,
					const char *destname, sam_conn_t style, uint_t tunneldepth);
extern void		sam_naming_lookup(const char *name);
extern bool		sam_read_buffer(void);
extern char		*sam_strerror(samerr_t code);
/* SAM controls - callbacks */
extern void		(*sam_diedback)(void);
extern void		(*sam_logback)(char *str);
extern void		(*sam_namingback)(char *name, sam_pubkey_t pubkey,
					samerr_t result);

/* Stream commands */
extern void		sam_stream_close(sam_sid_t stream_id);
extern sam_sid_t sam_stream_connect(const sam_pubkey_t dest);
extern samerr_t	sam_stream_send(sam_sid_t stream_id, const void *data,
					size_t size);
/* Stream commands - callbacks */
extern void		(*sam_closeback)(sam_sid_t stream_id, samerr_t reason);
extern void		(*sam_connectback)(sam_sid_t stream_id, sam_pubkey_t dest);
extern void		(*sam_databack)(sam_sid_t stream_id, void *data, size_t size);
extern void		(*sam_statusback)(sam_sid_t stream_id, samerr_t result);

/* Stream send queue */
extern samerr_t	sam_sendq_add(sam_sendq_t *sendq, const void *data,
					size_t dsize);
extern sam_sendq_t *sam_sendq_create(void);
extern void		sam_sendq_send(sam_sendq_t *sendq, sam_sid_t stream_id);

/* Datagram commands */
extern samerr_t	sam_dgram_send(const sam_pubkey_t dest, const void *data,
					size_t size);

/* Datagram commands - callbacks */
extern void		(*sam_dgramback)(sam_pubkey_t dest, void *data, size_t size);

#ifdef __cplusplus
}
#endif
#endif  /* SAM_H */
