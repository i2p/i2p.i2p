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

#include "sam.h"
#include "platform.h"
#include "parse.h"
#include "tinystring.h"

#include <assert.h>

static bool			sam_hello(sam_sess_t *session);
static void			sam_log(const char *format, ...);
static void			sam_parse(sam_sess_t *session, char *s);
static ssize_t		sam_read1(sam_sess_t *session, char *buf, size_t n);
static ssize_t		sam_read2(sam_sess_t *session, void *buf, size_t n);
static bool			sam_readable(sam_sess_t *session);
static sam_sendq_t	*sam_sendq_create();
static samerr_t		sam_session_create(sam_sess_t *session,
						const char *destname, sam_conn_t style,
						uint tunneldepth);
static bool			sam_socket_connect(sam_sess_t *session, const char *host,
						ushort port);
static bool			sam_socket_resolve(const char *hostname, char *ipaddr);
#ifdef WINSOCK
static samerr_t		sam_winsock_cleanup();
static samerr_t		sam_winsock_startup();
static const char	*sam_winsock_strerror(int code);
#endif
static ssize_t		sam_write(sam_sess_t *session, const void *buf, size_t n);

/*
 * Callback functions
 * Note: if you add a new callback be sure to check for non-NULL in sam_connect
 */

/* a peer closed the connection */
void (*sam_closeback)(sam_sess_t *session, sam_sid_t stream_id, samerr_t reason, const char* message)
	= NULL;

/* a peer connected to us */
void (*sam_connectback)(sam_sess_t *session, sam_sid_t stream_id,
	sam_pubkey_t dest) = NULL;

/* a peer sent some stream data (`data' MUST be freed) */
void (*sam_databack)(sam_sess_t *session, sam_sid_t stream_id, void *data,
	size_t size) = NULL;

/* a peer sent some datagram data (`data' MUST be freed) */
void (*sam_dgramback)(sam_sess_t *session, sam_pubkey_t dest, void *data,
	size_t size) = NULL;

/* we lost the connection to the SAM host */
void (*sam_diedback)(sam_sess_t *session) = NULL;

/* logging callback */
void (*sam_logback)(const char *str) = NULL;

/* naming lookup reply - `pubkey' will be NULL if `result' isn't SAM_OK */
void (*sam_namingback)(sam_sess_t *session, const char *name, sam_pubkey_t pubkey, samerr_t result, const char* message) = NULL;

/* our connection to a peer has completed */
void (*sam_statusback)(sam_sess_t *session, sam_sid_t stream_id,
	samerr_t result, const char* message) = NULL;

/* a peer sent some raw data (`data' MUST be freed) */
void (*sam_rawback)(sam_sess_t *session, void *data, size_t size) = NULL;


/*
 * Closes the connection to the SAM host
 *
 * Returns: true on success, false on failure
 */
bool sam_close(sam_sess_t *session)
{
	assert(session != NULL);
	if (!session->connected) {
#if SAM_WIRETAP
		SAMLOGS("Connection already closed - sam_close() skipped");
#endif
		return true;
	}

#ifdef WINSOCK
	if (closesocket(session->sock) == SOCKET_ERROR) {
		SAMLOG("Failed closing the SAM connection (%s)",
			sam_winsock_strerror(WSAGetLastError()));
		return false;
	}
	session->connected = false;
	if (sam_winsock_cleanup() == SAM_OK) {
#if SAM_WIRETAP
		SAMLOGS("Connection closed safely");
#endif
		return true;
	} else
		return false;
#else
	if (close(session->sock) == 0) {
		session->connected = false;
#if SAM_WIRETAP
		SAMLOGS("Connection closed safely");
#endif
		return true;
	} else {
		SAMLOG("Failed closing the SAM connection (%s)", strerror(errno));
		return false;
	}
#endif
}

/*
 * Connects to the SAM host
 *
 * session - an unused SAM session created by sam_session_init()
 * samhost - SAM host
 * samport - SAM port
 * destname - destination name for this program, or "TRANSIENT" for a random
 *		dest
 * style - the connection style (stream, datagram, or raw)
 * tunneldepth - length of the I2P tunnels created by this program (longer is
 *		more anonymous, but slower)
 *
 * Returns: SAM error code.  If SAM_OK, `session' will be ready for use.
 */
samerr_t sam_connect(sam_sess_t *session, const char *samhost, ushort samport,
	const char *destname, sam_conn_t style, uint tunneldepth)
{
	assert(session != NULL);
	samerr_t rc;

	if (style == SAM_STREAM) {
		if (sam_closeback == NULL || sam_connectback == NULL
				|| sam_databack == NULL || sam_diedback == NULL
				|| sam_logback == NULL || sam_namingback == NULL
				|| sam_statusback == NULL) {
			SAMLOGS("Please set callback functions before connecting");
			return SAM_CALLBACKS_UNSET;
		}
	} else if (style == SAM_DGRAM) {
		if (sam_dgramback == NULL || sam_diedback == NULL
				|| sam_logback == NULL || sam_namingback == NULL) {
			SAMLOGS("Please set callback functions before connecting");
			return SAM_CALLBACKS_UNSET;
		}
	} else if (style == SAM_RAW) {
		if (sam_diedback == NULL || sam_logback == NULL
				|| sam_namingback == NULL || sam_rawback == NULL) {
			SAMLOGS("Please set callback functions before connecting");
			return SAM_CALLBACKS_UNSET;
		}
	} else {
		SAMLOGS("Unknown connection style");
		return SAM_BAD_STYLE;
	}

#ifdef WINSOCK
	rc = sam_winsock_startup();
	if (rc != SAM_OK)
		return rc;
#endif

	if (!sam_socket_connect(session, samhost, samport)) {
#ifdef WINSOCK
		SAMLOG("Couldn't connect to SAM at %s:%u (%s)",
			samhost, samport, sam_winsock_strerror(WSAGetLastError()));
#else
		SAMLOG("Couldn't connect to SAM at %s:%u (%s)",
			samhost, samport, strerror(errno));
#endif
		SAMLOGS("Is your I2P router running?");
		return SAM_SOCKET_ERROR;
	}

	if (!sam_hello(session))
		return SAM_BAD_VERSION;

	rc = sam_session_create(session, destname, style, tunneldepth);
	if (rc != SAM_OK)
		return rc;

	SAMLOG("SAM connection open to %s:%u", samhost, samport);
	return SAM_OK;
}

/*
 * Sends data to a destination in a datagram
 *
 * dest - base 64 destination of who we're sending to
 * data - the data we're sending
 * size - the size of the data
 *
 * Returns: SAM_OK on success
 */
samerr_t sam_dgram_send(sam_sess_t *session, const sam_pubkey_t dest,
	const void *data, size_t size)
{
	assert(session != NULL);
	char cmd[SAM_PKCMD_LEN];

	if (size < 1 || size > SAM_DGRAM_PAYLOAD_MAX) {
#ifdef NO_Z_FORMAT
		SAMLOG("Invalid data send size (%u bytes)", size);
#else
		SAMLOG("Invalid data send size (%zu bytes)", size);
#endif
		return SAM_TOO_BIG;
	}
#ifdef NO_Z_FORMAT
	snprintf(cmd, sizeof cmd, "DATAGRAM SEND DESTINATION=%s SIZE=%u\n",
		dest, size);
#else
	snprintf(cmd, sizeof cmd, "DATAGRAM SEND DESTINATION=%s SIZE=%zu\n",
		dest, size);
#endif
	sam_write(session, cmd, strlen(cmd));
	sam_write(session, data, size);

	return SAM_OK;
}

/*
 * Sends the first SAM handshake command and checks the reply
 * (call sam_session_create() after this)
 *
 * Returns: true on success, false on reply failure
 */
static bool sam_hello(sam_sess_t *session)
{
	assert(session != NULL);
#define SAM_HELLO_CMD	"HELLO VERSION MIN=1.0 MAX=1.0\n"
#define SAM_HELLO_REPLY	"HELLO REPLY RESULT=OK VERSION=1.0"
	char reply[SAM_REPLY_LEN];

	sam_write(session, SAM_HELLO_CMD, strlen(SAM_HELLO_CMD));
	sam_read1(session, reply, SAM_REPLY_LEN);
	if (strncmp(reply, SAM_HELLO_REPLY, strlen(SAM_HELLO_REPLY)) == 0)
		return true;
	else {
		SAMLOGS("HELLO failed");
		return false;
	}
}

/*
 * Converts va arg logs into a standard string and runs the callback
 */
static void sam_log(const char *format, ...)
{
	va_list ap;
	char s[SAM_LOGMSG_LEN];

	va_start(ap, format);
	vsnprintf(s, sizeof s, format, ap);
	va_end(ap);
	/*strlcat(s, "\n", sizeof s);*/
	sam_logback(s);

	return;
}

/*
 * Performs a base 64 public key lookup on the specified `name'
 *
 * name - name to lookup, or ME to lookup our own name
 */
void sam_naming_lookup(sam_sess_t *session, const char *name)
{
    assert(session != NULL);
    char cmd[SAM_CMD_LEN];

    snprintf(cmd, sizeof cmd, "NAMING LOOKUP NAME=%s\n", name);
    sam_write(session, cmd, strlen(cmd));

    return;
}

/*
 * Parses incoming data and calls the appropriate callback
 *
 * s - string of data that we read (read past tense)
 */
bool sam_parse_args(sam_sess_t *session, args_t args);
static void sam_parse(sam_sess_t *session, char *s)
{
    //Wrapper for ease of memory management
    args_t args;
    assert(session != NULL);
    args = arg_parse(s);
    if(!sam_parse_args(session, args)) {
        SAMLOG("Unknown SAM command received: %s", s);
    }
    arg_done(args);
}

long int strtol_checked(const char* str) {
    static char* end = NULL;
    long int ret = strtol(str,&end,10);
    assert(str != end || "No number found at all!");    
    return ret;
}

bool sam_parse_args(sam_sess_t *session, args_t args)
{
    arg_t* arg; // The current argument being examined...
    const char* message = NULL; // Almost EVERYTHING can have a message...
	
    if(args.num <= 0) return 0;

#define ARG_IS(a,b) string_equal(AG(args,a)->name,string_wrap(b))
#define ARG_FIND(a) arg_find(args,_sw(a))

    // Almost EVERYTHING can have a message...
    arg = ARG_FIND("MESSAGE");
    if(arg) {
        message = string_data(arg->value);
    }

    if(ARG_IS(0,"DATAGRAM") &&
       ARG_IS(1,"RECEIVED")) {
        sam_pubkey_t dest;
        size_t size;
        void *data;

        arg = ARG_FIND("DESTINATION");
        assert(arg != NULL);
        _scr(arg->value, dest, sizeof dest);

        arg = ARG_FIND("SIZE");
        assert(arg != NULL);
        size = strtol_checked(string_data(arg->value));

        data = malloc(size + 1);  
        /* +1 for NUL termination, so when we are
           receiving a string it will just work and it
           won't be necessary to send NUL.  When binary
           data is sent, the extra NUL character will
           just be ignored by the client program,
           because it is not added to the size */
        if (data == NULL) {
	    SAMLOGS("Out of memory");
	    abort();
        }
        if (sam_read2(session, data, size) != -1) {
	    char* p = data + size;
	    *p = '\0';  /* see above NUL note */
	    sam_dgramback(session, dest, data, size); /* `data' must be freed */
        } else
	    free(data);
	  
    } else if (ARG_IS(0,"NAMING") &&
               ARG_IS(1, "REPLY")) {
        if(NULL == (arg = ARG_FIND("RESULT"))) {
            SAMLOGS("Naming reply with no result");
            return 0;
        }

        if (string_is(arg->value,"OK")) {
            sam_pubkey_t pubkey;
            arg = ARG_FIND("VALUE");
            assert(arg != NULL);
            _scr(arg->value, pubkey, sizeof pubkey);
            arg = ARG_FIND("NAME");
            assert(arg != NULL);

            sam_namingback(session, string_data(arg->value), pubkey, SAM_OK, message);
        } else if(string_is(arg->value,"INVALID_KEY")) {
            arg_t* namearg = ARG_FIND("NAME");
            assert(namearg != NULL);
            sam_namingback(session, string_data(namearg->value), NULL,
                           SAM_INVALID_KEY, message);
        } else if(string_is(arg->value,"KEY_NOT_FOUND")) {
            arg_t* namearg = ARG_FIND("NAME");
            assert(namearg != NULL);
            sam_namingback(session, string_data(namearg->value), NULL,
                           SAM_KEY_NOT_FOUND, message);
        } else {
            arg_t* namearg = ARG_FIND("NAME");
            assert(namearg != NULL);
            sam_namingback(session, string_data(namearg->value), NULL,
                           SAM_UNKNOWN, message);
        }
        
    } else if (ARG_IS(0,"STREAM")) {
        sam_sid_t stream_id;
        arg = ARG_FIND("ID");
        assert(arg != 0);
        stream_id = strtol_checked(string_data(arg->value));

        if(ARG_IS(1,"CLOSED")) {
            arg = ARG_FIND("RESULT");
            assert(arg != NULL);
            if (string_is(arg->value,"OK")) {
                sam_closeback(session, stream_id, SAM_OK, message);
            } else if (string_is(arg->value,"CANT_REACH_PEER")) {
                sam_closeback(session, stream_id, SAM_CANT_REACH_PEER, message);
            } else if (string_is(arg->value,"I2P_ERROR")) {
                sam_closeback(session, stream_id, SAM_I2P_ERROR, message);
            } else if (string_is(arg->value,"PEER_NOT_FOUND")) {
                sam_closeback(session, stream_id, SAM_PEER_NOT_FOUND, message);
            } else if (string_is(arg->value,"TIMEOUT")) {
                sam_closeback(session, stream_id, SAM_TIMEOUT, message);
            } else {
                sam_closeback(session, stream_id, SAM_UNKNOWN, message);
            }

	} else if(ARG_IS(1,"CONNECTED")) {
		sam_pubkey_t dest;

                arg = ARG_FIND("DESTINATION");
                assert(arg != NULL);
                _scr(arg->value, dest, sizeof dest);

		sam_connectback(session, stream_id, dest);

	} else if(ARG_IS(1,"RECEIVED")) {
		size_t size;
		void *data;

                arg = ARG_FIND("SIZE");
                assert(arg != NULL);
                size = strtol_checked(string_data(arg->value));

		data = malloc(size + 1);  
                /* +1 for NUL termination, so when we are
                   receiving a string it will just work and it
                   won't be necessary to send NUL.  When binary
                   data is sent, the extra NUL character will
                   just be ignored by the client program,
                   because it is not added to the size */
		if (data == NULL) {
			SAMLOGS("Out of memory");
			abort();
		}
		if (sam_read2(session, data, size) != -1) {
			char* p = data + size;
			*p = '\0';  /* see above NUL note */
			sam_databack(session, stream_id, data, size);
			/* ^^^ `data' must be freed ^^^*/
		} else
			free(data);

	} else if(ARG_IS(1,"STATUS")) {
            arg = ARG_FIND("RESULT");
            assert(arg != NULL);
            if (string_is(arg->value,"OK")) {
                sam_statusback(session, stream_id, SAM_OK, message);
            } else if (string_is(arg->value,"CANT_REACH_PEER")) {
                sam_statusback(session, stream_id, 
                               SAM_CANT_REACH_PEER, message);
            } else if (string_is(arg->value,"I2P_ERROR")) {
                sam_statusback(session, stream_id, SAM_I2P_ERROR, message);
            } else if (string_is(arg->value,"INVALID_KEY")) {
                sam_statusback(session, stream_id, SAM_INVALID_KEY, message);
            } else if (string_is(arg->value,"TIMEOUT")) {
                sam_statusback(session, stream_id, SAM_TIMEOUT, message);
            } else {
                sam_statusback(session, stream_id, SAM_UNKNOWN, message);
            }
	}
    } else
        return 0;
    return -1;
}

#undef ARG_IS
#undef ARG_FIND


/*
 * Sends data to a destination in a raw packet
 *
 * dest - base 64 destination of who we're sending to
 * data - the data we're sending
 * size - the size of the data
 *
 * Returns: SAM_OK on success
 */
samerr_t sam_raw_send(sam_sess_t *session, const sam_pubkey_t dest,
	const void *data, size_t size)
{
	assert(session != NULL);
	char cmd[SAM_PKCMD_LEN];

	if (size < 1 || size > SAM_RAW_PAYLOAD_MAX) {
#ifdef NO_Z_FORMAT
		SAMLOG("Invalid data send size (%u bytes)", size);
#else
		SAMLOG("Invalid data send size (%zu bytes)", size);
#endif
		return SAM_TOO_BIG;
	}
#ifdef NO_Z_FORMAT
	snprintf(cmd, sizeof cmd, "RAW SEND DESTINATION=%s SIZE=%u\n",
		dest, size);
#else
	snprintf(cmd, sizeof cmd, "RAW SEND DESTINATION=%s SIZE=%zu\n",
		dest, size);
#endif
	sam_write(session, cmd, strlen(cmd));
	sam_write(session, data, size);

	return SAM_OK;
}

/*
 * Reads and callbacks everything in the SAM network buffer until it is clear
 *
 * Returns: true if we read anything, or false if nothing was there
 */
bool sam_read_buffer(sam_sess_t *session)
{
	assert(session != NULL);
	bool read_something = false;
	char reply[SAM_REPLY_LEN];

	if (sam_readable(session)) {
		do {
			sam_read1(session, reply, SAM_REPLY_LEN);
			read_something = true;
			sam_parse(session, reply);
		} while (sam_readable(session));
	}

	return read_something;
}

/*
 * SAM command reader
 *
 * Reads up to `n' bytes of a SAM response into the buffer `buf' OR (preferred)
 * up to the first '\n' which is replaced with a NUL.  The resulting buffer will
 * always be NUL-terminated.
 * Warning: A full buffer will cause the last character read to be lost (it is
 * 		replaced with a NUL in the buffer).
 *
 * buf - buffer
 * n - number of bytes available in `buf', the buffer
 *
 * Returns: number of bytes read, or -1 on error
 */
static ssize_t sam_read1(sam_sess_t *session, char *buf, size_t n)
{
	assert(session != NULL);
	size_t nleft;
	ssize_t nread;
	char *p;

	*buf = '\0';  /* this forces `buf' to be a string even if there is a
					 sam_read1 error return */
	if (!session->connected) {
		SAMLOGS("Cannot read from SAM because the SAM connection is closed");
		sam_diedback(session);
		return -1;
	}
	assert(n > 0);
	p = buf;
	nleft = n;
	while (nleft > 0) {
		nread = recv(session->sock, p, 1, 0);
		if (nread == -1) {
			if (errno == EINTR)  /* see Unix Network Pgming vol 1, Sec. 5.9 */
				continue;
			else {
#ifdef WINSOCK
				SAMLOG("recv() failed: %s",
					sam_winsock_strerror(WSAGetLastError()));
#else
				SAMLOG("recv() failed: %s", strerror(errno));
#endif
				sam_close(session);
				sam_diedback(session);
				return -1;
			}
		} else if (nread == 0) {  /* EOF */
			SAMLOGS("Connection closed by the SAM host");
			sam_close(session);
			sam_diedback(session);
			return -1;
		}
		assert(nread == 1);
		nleft--;
		if (*p == '\n') {  /* end of SAM response */
			*p = '\0';
#if SAM_WIRETAP
			printf("*RR* %s\n", buf);
#endif
			return n - nleft;
		}
		p++;
	}
	/* buffer full, which is considered an error */
	SAMLOGS("sam_read1() buf full");
	p--;
	*p = '\0';  /* yes, this causes the loss of the last character */

	return n - nleft;  /* return >= 0 */
}

/*
 * SAM payload reader
 *
 * Reads exactly `n' bytes of a SAM data payload into the buffer `buf'
 *
 * buf - buffer
 * n - number of bytes available in `buf'
 *
 * Returns: number of bytes read, or -1 on error
 */
static ssize_t sam_read2(sam_sess_t *session, void *buf, size_t n)
{
	assert(session != NULL);
	size_t nleft;
	ssize_t nread;
	void *p;

	if (!session->connected) {
		SAMLOGS("Cannot read from SAM because the SAM connection is closed");
		sam_diedback(session);
		return -1;
	}
	assert(n > 0);
	p = buf;
	nleft = n;
	while (nleft > 0) {
		nread = recv(session->sock, p, nleft, 0);
		if (nread == -1) {
			if (errno == EINTR)  /* see Unix Network Pgming vol 1, Sec. 5.9 */
				continue;
			else {
#ifdef WINSOCK
				SAMLOG("recv() failed: %s",
					sam_winsock_strerror(WSAGetLastError()));
#else
				SAMLOG("recv() failed: %s", strerror(errno));
#endif
				sam_close(session);
				sam_diedback(session);
				return -1;
			}
		} else if (nread == 0) {  /* EOF */
			SAMLOGS("Connection closed by the SAM host");
			sam_close(session);
			sam_diedback(session);
			return -1;
		}
		nleft -= nread;
		p += nread;
	}
#if SAM_WIRETAP
	p = buf;
	printf("*RR* ");
	for (size_t x = 0; x < n; x++) {
		if (isprint(((byte*)p)[x]))
			printf("%c,", ((byte*)p)[x]);
		else
			printf("%03d,", ((byte*)p)[x]);
	}
	printf("\n");
	printf("*RR* (read2() read %d bytes)\n", n);
#endif
	assert(nleft == 0);/*  <---\                       */
	return n - nleft;  /* should be equal to initial n */
}

/*
 * Checks if there is incoming data waiting to be read from the SAM connection
 *
 * Returns: true if data is waiting, false otherwise
 */
static bool sam_readable(sam_sess_t *session)
{
	assert(session != NULL);
	fd_set rset;  /* set of readable descriptors */
	struct timeval tv;
	int rc;

	if (!session->connected) {
		SAMLOGS("Cannot read from SAM because the SAM connection is closed");
		sam_diedback(session);
		return false;
	}
	FD_ZERO(&rset);
	FD_SET(session->sock, &rset);
	tv.tv_sec = 0;
	tv.tv_usec = 0;
	rc = select(session->sock + 1, &rset, NULL, NULL, &tv);
	if (rc == 0)
		return false;
	else if (rc > 0)
		return true;
	else {
#ifdef WINSOCK
		SAMLOG("select() failed: %s", sam_winsock_strerror(WSAGetLastError()));
#else
		SAMLOG("select() failed: %s", strerror(errno));
#endif
		return false;
	}
}

/*
 * Adds data to the send queue
 *
 * stream_id - stream number to send to if the queue is full
 * sendq - the send queue
 * data - data to add
 * dsize - the size of the data
 *
 * Returns: true on success, false on error
 */
void sam_sendq_add(sam_sess_t *session, sam_sid_t stream_id,
	sam_sendq_t **sendq, const void *data, size_t dsize)
{
	assert(session != NULL);
	assert(dsize >= 0);
	if (dsize == 0) {
		SAMLOGS("dsize is 0 - doing nothing");
		return;
	}

	/* if the sendq pointer is set to NULL, create a sendq */
	if (*sendq == NULL)
		*sendq = sam_sendq_create();

	/* the added data doesn't fill the queue - add but don't send */
	if ((*sendq)->size + dsize < SAM_STREAM_PAYLOAD_MAX) {
		memcpy((*sendq)->data + (*sendq)->size, data, dsize);
		(*sendq)->size += dsize;
		return;
	}

	/* what luck! - an exact fit - send the packet */
	if ((*sendq)->size + dsize == SAM_STREAM_PAYLOAD_MAX) {
		memcpy((*sendq)->data + (*sendq)->size, data, dsize);
		(*sendq)->size = SAM_STREAM_PAYLOAD_MAX;
		sam_sendq_flush(session, stream_id, sendq);
		return;
	}	

	/* they have more data than the queue can hold, so we'll have to send some*/
	size_t s = SAM_STREAM_PAYLOAD_MAX - (*sendq)->size;  // space free in packet
	memcpy((*sendq)->data + (*sendq)->size, data, s); //append as much as we can
	dsize -= s;  /* update dsize to the size of whatever data hasn't been sent*/
	(*sendq)->size = SAM_STREAM_PAYLOAD_MAX;  /* it's a full packet */
	sam_sendq_flush(session, stream_id, sendq);  /* send the queued data */
	sam_sendq_add(session, stream_id, sendq, data + s, dsize);  /* recurse */

	return;
}

/*
 * Creates a data queue for use with sam_sendq_add()
 *
 * Returns: pointer to the newly created send queue
 */
static sam_sendq_t *sam_sendq_create()
{
	sam_sendq_t *sendq;

	sendq = malloc(sizeof(sam_sendq_t));
	if (sendq == NULL) {
		SAMLOGS("Out of memory");
		abort();
	}
	sendq->data = malloc(SAM_STREAM_PAYLOAD_MAX);
	if (sendq->data == NULL) {
		SAMLOGS("Out of memory");
		abort();
	}
	/* ^^ a waste of memory perhaps, but more efficient than realloc'ing every
	 * time data is added the to queue */
	sendq->size = 0;

	return sendq;
}

/*
 * Sends the data in the send queue to the specified stream
 *
 * stream_id - stream number to send to
 * sendq - the send queue
 */
void sam_sendq_flush(sam_sess_t *session, sam_sid_t stream_id,
	sam_sendq_t **sendq)
{
	assert(session != NULL);
	sam_stream_send(session, stream_id, (*sendq)->data, (*sendq)->size);
	/* we now free it in case they aren't going to use it anymore */
	free((*sendq)->data);
	free(*sendq);
	*sendq = NULL;

	return;
}

/*
 * Sends the second SAM handshake command and checks the reply
 *
 * destname - destination name for this program, or "TRANSIENT" to create a
 * 		random temporary destination
 * style - type of connection to use (SAM_STREAM, SAM_DGRAM, or SAM_RAW)
 * tunneldepth - length of the I2P tunnels created by this program
 *
 * Returns: SAM error code
 */
static samerr_t sam_session_create(sam_sess_t *session, const char *destname,
	sam_conn_t style, uint tunneldepth)
{
	assert(session != NULL);
#define SAM_SESSTATUS_REPLY_OK "SESSION STATUS RESULT=OK"
#define SAM_SESSTATUS_REPLY_DD "SESSION STATUS RESULT=DUPLICATED_DEST"
#define SAM_SESSTATUS_REPLY_I2E "SESSION STATUS RESULT=I2P_ERROR"
#define SAM_SESSTATUS_REPLY_IK "SESSION STATUS RESULT=INVALID_KEY"
	char cmd[SAM_CMD_LEN * 2];
	char reply[SAM_REPLY_LEN];

	if (style == SAM_STREAM) {
		snprintf(cmd, sizeof cmd,
			"SESSION CREATE STYLE=STREAM DESTINATION=%s " \
			"tunnels.depthInbound=%u " \
			"tunnels.depthOutbound=%u\n",
			destname, tunneldepth, tunneldepth);
	} else if (style == SAM_DGRAM) {
		snprintf(cmd, sizeof cmd,
			"SESSION CREATE STYLE=DATAGRAM DESTINATION=%s " \
			"i2cp.messageReliability=BestEffort " \
			"tunnels.depthInbound=%u " \
			"tunnels.depthOutbound=%u\n",
			destname, tunneldepth, tunneldepth);
	} else { /* SAM_RAW */
		abort();  /* unimplemented */
	}

	sam_write(session, cmd, strlen(cmd));
	sam_read1(session, reply, SAM_REPLY_LEN);
	if (strncmp(reply, SAM_SESSTATUS_REPLY_OK,
			strlen(SAM_SESSTATUS_REPLY_OK)) == 0)
		return SAM_OK;
	else if (strncmp(reply, SAM_SESSTATUS_REPLY_DD,
			strlen(SAM_SESSTATUS_REPLY_DD)) == 0)
		return SAM_DUPLICATED_DEST;
	else if (strncmp(reply, SAM_SESSTATUS_REPLY_I2E,
			strlen(SAM_SESSTATUS_REPLY_I2E)) == 0)
		return SAM_I2P_ERROR;
	else if (strncmp(reply, SAM_SESSTATUS_REPLY_IK,
			strlen(SAM_SESSTATUS_REPLY_IK)) == 0)
		return SAM_INVALID_KEY;
	else
		return SAM_UNKNOWN;
}

/*
 * Allocates memory for the session and sets its default values
 *
 * session - pointer to a previously allocated sam_sess_t to initalise, or NULL
 *		if you want memory to be allocated by this function
 */
sam_sess_t *sam_session_init(sam_sess_t *session)
{
	if (session == NULL) {
		session = malloc(sizeof(sam_sess_t));
		if (session == NULL) {
			SAMLOGS("Out of memory");
			abort();
		}
		session->child = NULL;
	}
	session->connected = false;
	session->prev_id = 0;

	return session;
}

/*
 * Frees memory used by the session and sets the pointer to NULL
 *
 * session - pointer to a pointer to a sam_sess_t
 */
void sam_session_free(sam_sess_t **session)
{
	assert(*session != NULL);
	free(*session);
	*session = NULL;
}

/*
 * Connects to a remote host and returns a connected descriptor
 *
 * host - host name or ip address
 * port - port number
 *
 * Returns: true on sucess, false on error, with errno set
 */
bool sam_socket_connect(sam_sess_t *session, const char *host, ushort port)
{
	assert(session != NULL);
	struct sockaddr_in hostaddr;
	int rc;
	char ipaddr[INET_ADDRSTRLEN];

	session->sock = socket(AF_INET, SOCK_STREAM, 0);
#ifdef WINSOCK
	if (session->sock == INVALID_SOCKET) {
		SAMLOG("socket() failed: %s", sam_winsock_strerror(WSAGetLastError()));
		return false;
	}
#else
	if (session->sock == -1) {
		SAMLOG("socket() failed: %s", strerror(errno));
		return false;
	}
#endif
	memset(&hostaddr, 0, sizeof hostaddr);
	hostaddr.sin_family = AF_INET;
	hostaddr.sin_port = htons(port);
	if (!sam_socket_resolve(host, ipaddr))
		return false;
#ifdef NO_INET_ATON
	rc = hostaddr.sin_addr.s_addr = inet_addr(ipaddr);
#elif defined NO_INET_PTON
	rc = inet_aton(ipaddr, &hostaddr.sin_addr);
#else
	rc = inet_pton(AF_INET, ipaddr, &hostaddr.sin_addr);
#endif
	if (rc == 0) {
		errno = EINVAL;
		return false;
	} else if (rc == -1)
		return false;

	rc = connect(session->sock, (struct sockaddr *)&hostaddr, sizeof hostaddr);
	if (rc == -1) {
#ifdef WINSOCK
		SAMLOG("connect() failed: %s", sam_winsock_strerror(WSAGetLastError()));
#else
		SAMLOG("connect() failed: %s", strerror(errno));
#endif
		return false;
	}

	session->connected = true;
	return true;
}

/*
 * Perform a DNS lookup on a hostname
 *
 * hostname - hostname to resolve
 * ipaddr - filled with the ip address
 *
 * Returns: true on success, false on failure
 */
static bool sam_socket_resolve(const char *hostname, char *ipaddr)
{
	struct hostent *h;
	struct in_addr a;

retry:
#ifdef NO_GETHOSTBYNAME2
	h = gethostbyname(hostname);
#else
	h = gethostbyname2(hostname, AF_INET);
#endif
	if (h == NULL) {
#ifdef WINSOCK
		if (WSAGetLastError() == WSATRY_AGAIN) {
			Sleep(1000);
#else
		if (h_errno == TRY_AGAIN) {
			sleep(1);
#endif
			goto retry;
		} else {
			SAMLOG("DNS resolution failed for %s", hostname);
#ifdef WINSOCK
			WSASetLastError(WSAHOST_NOT_FOUND);
#else
			errno = ENOENT;
#endif
			return false;
		}
	}
	a.s_addr = ((struct in_addr *)h->h_addr)->s_addr;
#ifdef NO_INET_NTOP
	/* inet_ntoa() was very poorly designed! */
	char *tmp;
	tmp = inet_ntoa(a);
	assert(tmp != NULL);
	strlcpy(ipaddr, tmp, INET_ADDRSTRLEN);
	return true;
#else
	if (inet_ntop(AF_INET, &a, ipaddr, INET_ADDRSTRLEN) != NULL) {
		return true;
	} else {
		SAMLOG("inet_ntop() failed: %s", strerror(errno));
		return false;
	}
#endif
}		

/*
 * Closes the specified stream number
 *
 * stream_id - stream number to close
 */
void sam_stream_close(sam_sess_t *session, sam_sid_t stream_id)
{
	assert(session != NULL);
	char cmd[SAM_CMD_LEN];

	snprintf(cmd, sizeof cmd, "STREAM CLOSE ID=%ld\n", stream_id);
	sam_write(session, cmd, strlen(cmd));

	return;
}

/*
 * Opens a new stream connection to public key destination `dest'
 *
 * dest - base 64 destination
 *
 * Returns: stream id number
 */
sam_sid_t sam_stream_connect(sam_sess_t *session, const sam_pubkey_t dest)
{
	assert(session != NULL);
	char cmd[SAM_PKCMD_LEN];

	session->prev_id++;  /* increment the id for the connection */
	snprintf(cmd, sizeof cmd, "STREAM CONNECT ID=%ld DESTINATION=%s\n",
		session->prev_id, dest);
	sam_write(session, cmd, strlen(cmd));

	return session->prev_id;
}

/*
 * Sends data to a SAM stream
 *
 * stream_id - the stream number to send to
 * data - the data to send
 * size - the size of the data
 *
 * Returns: true on success, false on failure
 */
samerr_t sam_stream_send(sam_sess_t *session, sam_sid_t stream_id,
	const void *data, size_t size)
{
	assert(session != NULL);
	char cmd[SAM_CMD_LEN];

	if (size < 1 || size > SAM_STREAM_PAYLOAD_MAX) {
#ifdef NO_Z_FORMAT
		SAMLOG("Invalid data send size (%u bytes) for stream %d",
			size, stream_id);
#else
		SAMLOG("Invalid data send size (%zu bytes) for stream %d",
			size, stream_id);
#endif
		return SAM_TOO_BIG;
	}
#ifdef NO_Z_FORMAT
	snprintf(cmd, sizeof cmd, "STREAM SEND ID=%ld SIZE=%u\n", stream_id, size);
#else
	snprintf(cmd, sizeof cmd, "STREAM SEND ID=%ld SIZE=%zu\n",
		stream_id, size);
#endif
	sam_write(session, cmd, strlen(cmd));
	sam_write(session, data, size);

	return SAM_OK;
}

/*
 * Converts a SAM error code into a short error message
 *
 * code - the error code
 *
 * Returns: error string
 */
const char *sam_strerror(samerr_t code)
{
	switch (code) {
		case SAM_OK:				/* Operation completed succesfully */
			return "Ok";
		case SAM_CANT_REACH_PEER:	/* The peer exists, but cannot be reached */
			return "Can't reach peer";
		case SAM_DUPLICATED_DEST:	/* The specified Destination is already in
										use by another SAM instance */
			return "Duplicated destination";
		case SAM_I2P_ERROR:			/* A generic I2P error (e.g. I2CP
										disconnection, etc.) */
			return "I2P error";
		case SAM_INVALID_KEY:		/* The specified key is not valid (bad
										format, etc.) */
			return "Invalid key";
		case SAM_KEY_NOT_FOUND:		/* The naming system can't resolve the
										given name */
			return "Key not found";
		case SAM_PEER_NOT_FOUND:	/* The peer cannot be found on the network*/
			return "Peer not found";
		case SAM_TIMEOUT:			/* Timeout while waiting for an event (e.g.
										peer answer) */
			return "Timeout";
		/*
		 * SAM_UNKNOWN deliberately left out (goes to default)
		 */
		case SAM_BAD_STYLE:			/* Style must be stream, datagram, or raw */
			return "Bad connection style";
		case SAM_BAD_VERSION:		/* sam_hello() had an unexpected reply */
			return "Not a SAM port, or bad SAM version";
		case SAM_CALLBACKS_UNSET:	/* Some callbacks are still set to NULL */
			return "Callbacks unset";
		case SAM_SOCKET_ERROR:		/* TCP/IP connection to the SAM host:port
										failed */
			return "Socket error";
		case SAM_TOO_BIG:			/* A function was passed too much data */
			return "Data size too big";
		default:					/* An unexpected error happened */
			return "Unknown error";
	}
}

#ifdef WINSOCK
/*
 * Unloads the Winsock network subsystem
 *
 * Returns: SAM error code
 */
samerr_t sam_winsock_cleanup()
{
	if (WSACleanup() == SOCKET_ERROR) {
		SAMLOG("WSACleanup() failed: %s",
			sam_winsock_strerror(WSAGetLastError()));
    	return SAM_SOCKET_ERROR;		
	}

	return SAM_OK;
}

/*
 * Loads the Winsock network sucksystem
 *
 * Returns: SAM error code
 */
samerr_t sam_winsock_startup()
{
	/*
	 * Is Windows retarded or what?
	 */
	WORD wVersionRequested;
	WSADATA wsaData;
	int rc;

	wVersionRequested = MAKEWORD(2, 2);
	rc = WSAStartup(wVersionRequested, &wsaData);
	if (rc != 0) {
		SAMLOG("WSAStartup() failed: %s", sam_winsock_strerror(rc));
    	return SAM_SOCKET_ERROR;
	}
	if (LOBYTE(wsaData.wVersion) != 2 || HIBYTE(wsaData.wVersion) != 2) {
		SAMLOGS("Bad Winsock version");
		sam_winsock_cleanup();
    	return SAM_SOCKET_ERROR;
	}

	return SAM_OK;
}

/*
 * Apparently Winsock does not have a strerror() equivalent for its functions
 *
 * code - code from WSAGetLastError()
 *
 * Returns: error string (from http://msdn.microsoft.com/library/default.asp?
 *		url=/library/en-us/winsock/winsock/windows_sockets_error_codes_2.asp)
 */
const char *sam_winsock_strerror(int code)
{
	switch (code) {
		case WSAEINTR:
			return "Interrupted function call";
		case WSAEACCES:  // yes, that is the correct spelling
			return "Permission denied";
		case WSAEFAULT:
			return "Bad address";
		case WSAEINVAL:
			return "Invalid argument";
		case WSAEMFILE:
			return "Too many open files";
		case WSAEWOULDBLOCK:
			return "Resource temporarily unavailable";
		case WSAEINPROGRESS:
			return "Operation now in progress";
		case WSAEALREADY:
			return "Operation already in progress";
		case WSAENOTSOCK:
			return "Socket operations on nonsocket";
		case WSAEDESTADDRREQ:
			return "Destination address required";
		case WSAEMSGSIZE:
			return "Message too long";
		case WSAEPROTOTYPE:
			return "Protocol wrong type for socket";
		case WSAENOPROTOOPT:
			return "Bad protocol option";
		case WSAEPROTONOSUPPORT:
			return "Protocol not supported";
		case WSAESOCKTNOSUPPORT:
			return "Socket type not supported";
		case WSAEOPNOTSUPP:
			return "Operation not supported";
		case WSAEPFNOSUPPORT:
			return "Protocol family not supported";
		case WSAEAFNOSUPPORT:
			return "Address family not supported by protocol family";
		case WSAEADDRINUSE:
			return "Address already in use";
		case WSAEADDRNOTAVAIL:
			return "Cannot assign requested address";
		case WSAENETDOWN:
			return "Network is down";
		case WSAENETUNREACH:
			return "Network is unreachable";
		case WSAENETRESET:
			return "Network dropped connection on reset";
		case WSAECONNABORTED:
			return "Software caused connection abort";
		case WSAECONNRESET:
			return "Connection reset by peer";
		case WSAENOBUFS:
			return "No buffer space available";
		case WSAEISCONN:
			return "Socket is already connected";
		case WSAENOTCONN:
			return "Socket is not connected";
		case WSAESHUTDOWN:
			return "Cannot send after socket shutdown";
		case WSAETIMEDOUT:
			return "Connection timed out";
		case WSAECONNREFUSED:
			return "Connection refused";
		case WSAEHOSTDOWN:
			return "Host is down";
		case WSAEHOSTUNREACH:
			return "No route to host";
		case WSAEPROCLIM:
			return "Too many processes";
		case WSASYSNOTREADY:
			return "Network subsystem is unavailable";
		case WSAVERNOTSUPPORTED:
			return "Winsock.dll version out of range";
		case WSANOTINITIALISED:
			return "Successful WSAStartup not yet performed";
		case WSAEDISCON:
			return "Graceful shutdown in progress";
		case WSATYPE_NOT_FOUND:
			return "Class type not found";
		case WSAHOST_NOT_FOUND:
			return "Host not found";
		case WSATRY_AGAIN:
			return "Nonauthoritative host not found";
		case WSANO_RECOVERY:
			return "This is a nonrecoverable error";
		case WSANO_DATA:
			return "Valid name, no data record of requested type";
		/* None of this shit compiles under Mingw - who knows why...
		case WSA_INVALID_HANDLE:
			return "Specified event object handle is invalid";
		case WSA_INVALID_PARAMETER:
			return "One or more parameters are invalid";
		case WSA_IO_INCOMPLETE:
			return "Overlapped I/O event object not in signaled state";
		case WSA_IO_PENDING:
			return "Overlapped operations will complete later";
		case WSA_NOT_ENOUGH_MEMORY:
			return "Insufficient memory available";
		case WSA_OPERATION_ABORTED:
			return "Overlapped operation aborted";
		case WSAINVALIDPROCTABLE:
			return "Invalid procedure table from service provider";
		case WSAINVALIDPROVIDER:
			return "Invalid service provider version number";
		case WSAPROVIDERFAILEDINIT:
			return "Unable to initialize a service provider"; */
		case WSASYSCALLFAILURE:
			return "System call failure";
		default:
			return "Unknown error";
	}
}
#endif

/*
 * Sends `n' bytes to the SAM host
 *
 * buf - buffer with the data in it
 * n - bytes to send
 *
 * Returns: `n', or -1 on error
 */
static ssize_t sam_write(sam_sess_t *session, const void *buf, size_t n)
{
	assert(session != NULL);
	size_t nleft;
	ssize_t nwritten;
	const char *p;

	if (!session->connected) {
		SAMLOGS("Cannot write to SAM because the SAM connection is closed");
		sam_diedback(session);
		return -1;
	}
#if SAM_WIRETAP
	const byte *cp = buf;
	printf("*WW* ");
	for (size_t x = 0; x < n; x++) {
		if (isprint(cp[x]))
			printf("%c,", cp[x]);
		else
			printf("%03d,", cp[x]);
	}
	printf("\n");
#endif
	p = buf;
	nleft = n;
	while (nleft > 0) {
		nwritten = send(session->sock, p, nleft, 0);
		if (nwritten <= 0) {
			if (errno == EINTR)  /* see Unix Network Pgming vol 1, Sec. 5.9 */
				continue;
			else {
#ifdef WINSOCK
				SAMLOG("send() failed: %s",
					sam_winsock_strerror(WSAGetLastError()));
#else
				SAMLOG("send() failed: %s", strerror(errno));
#endif
				sam_close(session);
				sam_diedback(session);
				return -1;
			}
		}
		nleft -= nwritten;
		p += nwritten;
	}
#if SAM_WIRETAP
	printf("*WW* (write() wrote %d bytes)\n", n);
#endif

	return n;
}
