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

#include "platform.h"
#include "sam.h"

static bool			sam_hello(void);
static void			sam_log(const char *format, ...);
static void			sam_parse(char *s);
static ssize_t		sam_read1(char *buf, size_t n);
static ssize_t		sam_read2(void *buf, size_t n);
static bool			sam_readable(void);
static samerr_t		sam_session_create(const char *destname, sam_conn_t style,
						uint_t tunneldepth);
static bool			sam_socket_connect(const char *host, uint16_t port);
static bool			sam_socket_resolve(const char *hostname, char *ipaddr);
static ssize_t		sam_write(const void *buf, size_t n);

/*
 * Callback functions
 * Note: if you add a new callback be sure to check for non-NULL in sam_connect
 */
/* a peer closed the connection */
void (*sam_closeback)(sam_sid_t stream_id, samerr_t reason) = NULL;
/* a peer connected to us */
void (*sam_connectback)(sam_sid_t stream_id, sam_pubkey_t dest) = NULL;
/* a peer sent some stream data (`data' MUST be freed) */
void (*sam_databack)(sam_sid_t stream_id, void *data, size_t size) = NULL;
/* a peer sent some datagram data (`data' MUST be freed) */
void (*sam_dgramback)(sam_pubkey_t dest, void *data, size_t size) = NULL;
/* we lost the connection to the SAM host */
void (*sam_diedback)(void) = NULL;
/* logging callback */
void (*sam_logback)(char *str) = NULL;
/* naming lookup reply - `pubkey' will be NULL if `result' isn't SAM_OK */
void (*sam_namingback)(char *name, sam_pubkey_t pubkey,
	samerr_t result) = NULL;
/* our connection to a peer has completed */
void (*sam_statusback)(sam_sid_t stream_id, samerr_t result) = NULL;

static socket_t samd;  /* The socket descriptor we're using for
								communications with SAM */
static bool samd_connected = false;  /* Whether we're connected with SAM */

/*
 * Closes the connection to the SAM host
 *
 * Returns: true on success, false on failure
 */
bool sam_close(void)
{
	if (!samd_connected)
		return true;

#ifdef WINSOCK
	if (closesocket(samd) == 0) {
		samd_connected = false;
		return true;
#else
	if (close(samd) == 0) {
		samd_connected = false;
		return true;
#endif
	} else {
		SAMLOG("Failed closing the SAM connection (%s)", strerror(errno));
		return false;
	}
}

/*
 * Connects to the SAM host
 *
 * samhost - SAM host
 * samport - SAM port
 * destname - destination name for this program, or "TRANSIENT" for a random
 *		dest
 * tunneldepth - length of the I2P tunnels created by this program (longer is
 *		more anonymous, but slower)
 *
 * Returns: true on success, false on failure
 */
samerr_t sam_connect(const char *samhost, uint16_t samport,
	const char *destname, sam_conn_t style, uint_t tunneldepth)
{
	samerr_t rc;

	if (style == SAM_STREAM) {
		if (sam_closeback == NULL || sam_connectback == NULL ||
				sam_databack == NULL || sam_diedback == NULL ||
				sam_logback == NULL || sam_namingback == NULL ||
				sam_statusback == NULL) {
			SAMLOGS("Please set callback functions before connecting");
			return SAM_CALLBACKS_UNSET;
		}
	} else if (style == SAM_DGRAM) {
		if (sam_dgramback == NULL || sam_diedback == NULL ||
				sam_logback == NULL || sam_namingback == NULL) {
			SAMLOGS("Please set callback functions before connecting");
			return SAM_CALLBACKS_UNSET;
		}
	} else if (style == SAM_RAW) {
		assert(false);  /* not implemented yet */
	} else {
		assert(false);  /* unknown style */
	}

#ifdef WINSOCK
	/*
	 * Is Windows retarded or what?
	 */
	WORD wVersionRequested;
	WSADATA wsaData;
	int err;

	wVersionRequested = MAKEWORD(1, 1);
	err = WSAStartup(wVersionRequested, &wsaData);
	if (err != 0) {
		SAMLOGS("WSAStartup() failed");
    	return SAM_SOCKET_ERROR;
	}
	if (LOBYTE(wsaData.wVersion) != 1 || HIBYTE(wsaData.wVersion) != 1 ) {
		SAMLOGS("Bad WinSock version");
    	return SAM_SOCKET_ERROR;
	}
#endif

	if (!sam_socket_connect(samhost, samport)) {
		SAMLOG("Couldn't connect to SAM at %s:%u (%s)",
			samhost, samport, strerror(errno));
		SAMLOGS("Is your I2P router running?");
		return SAM_SOCKET_ERROR;
	}

	if (!sam_hello())
		return SAM_BAD_VERSION;

	rc = sam_session_create(destname, style, tunneldepth);
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
 * Returns: true on success, false on failure
 */
samerr_t sam_dgram_send(const sam_pubkey_t dest, const void *data, size_t size)
{
	char cmd[SAM_PKCMD_LEN];

	if (size < 1 || size > SAM_DGRAM_PAYLOAD_MAX) {
#ifdef NO_Z_FORMAT
		SAMLOG("Invalid data send size (%u bytes)", size);
#else
		SAMLOG("Invalid data send size (%z bytes)", size);
#endif
		return SAM_TOO_BIG;
	}
#ifdef NO_Z_FORMAT
	snprintf(cmd, sizeof cmd, "DATAGRAM SEND DESTINATION=%s SIZE=%u\n",
		dest, size);
#else
	snprintf(cmd, sizeof cmd, "DATAGRAM SEND DESTINATION=%s SIZE=%z\n",
		dest, size);
#endif
	sam_write(cmd, strlen(cmd));
	sam_write(data, size);

	return SAM_OK;
}

/*
 * Sends the first SAM handshake command and checks the reply
 * (call sam_session_create() after this)
 *
 * Returns: true on success, false on reply failure
 */
static bool sam_hello(void)
{
#define SAM_HELLO_CMD	"HELLO VERSION MIN=1.0 MAX=1.0\n"
#define SAM_HELLO_REPLY	"HELLO REPLY RESULT=OK VERSION=1.0"
	char reply[SAM_REPLY_LEN];

	sam_write(SAM_HELLO_CMD, strlen(SAM_HELLO_CMD));
	sam_read1(reply, SAM_REPLY_LEN);
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
void sam_naming_lookup(const char *name)
{
	char cmd[SAM_CMD_LEN];

	snprintf(cmd, sizeof cmd, "NAMING LOOKUP NAME=%s\n", name);
	sam_write(cmd, strlen(cmd));

	return;
}

/*
 * Parses incoming data and calls the appropriate callback
 *
 * s - string of data that we read (read past tense)
 */
static void sam_parse(char *s)
{
#define SAM_DGRAM_RECEIVED_REPLY "DATAGRAM RECEIVED"
#define SAM_NAMING_REPLY "NAMING REPLY"
#define SAM_NAMING_REPLY_OK "NAMING REPLY RESULT=OK"
#define SAM_NAMING_REPLY_IK "NAMING REPLY RESULT=INVALID_KEY"
#define SAM_NAMING_REPLY_KNF "NAMING REPLY RESULT=KEY_NOT_FOUND"
#define SAM_STREAM_CLOSED_REPLY "STREAM CLOSED"
#define SAM_STREAM_CONNECTED_REPLY "STREAM CONNECTED"
#define SAM_STREAM_RECEIVED_REPLY "STREAM RECEIVED"
#define SAM_STREAM_STATUS_REPLY "STREAM STATUS"
#define SAM_STREAM_STATUS_REPLY_OK "STREAM STATUS RESULT=OK"
#define SAM_STREAM_STATUS_REPLY_CRP "STREAM STATUS RESULT=CANT_REACH_PEER"
#define SAM_STREAM_STATUS_REPLY_I2E "STREAM STATUS RESULT=I2P_ERROR"
#define SAM_STREAM_STATUS_REPLY_IK "STREAM STATUS RESULT=INVALID_KEY"
#define SAM_STREAM_STATUS_REPLY_TO "STREAM STATUS RESULT=TIMEOUT"

	if (strncmp(s, SAM_DGRAM_RECEIVED_REPLY,
			strlen(SAM_DGRAM_RECEIVED_REPLY)) == 0) {
		char *p;
		sam_pubkey_t dest;
		size_t size;
		void *data;

		p = strchr(s, '=');  /* DESTINATION= */
		assert(p != NULL);
		p++;
		strlcpy(dest, p, sizeof dest);
		p = strchr(p, '=');  /* SIZE= */
		assert(p != NULL);
		p++;
		size = strtol(p, NULL, 10);
		assert(size != 0);
		data = malloc(size + 1);  /* +1 for NUL termination, so when we are
									receiving a string it will just work and it
									won't be necessary to send NUL.  When binary
									data is sent, the extra NUL character will
									just be ignored by the client program,
									because it is not added to the size */
		if (sam_read2(data, size) != -1) {
			p = data + size;
			*p = '\0';  /* see above NUL note */
			sam_dgramback(dest, data, size);  /* `data' must be freed */
		} else
			free(data);

		return;

	} else if (strncmp(s, SAM_NAMING_REPLY, strlen(SAM_NAMING_REPLY)) == 0) {
		char *p;
		char *q;
		char name[SAM_NAME_LEN];

		p = strchr(s, '=');  /* can't use strrchar because of option
								MESSAGE= */
		assert(p != NULL);  /* RESULT= */
		p++;
		p = strchr(p, '=');  /* NAME= */
		assert(p != NULL);
		p++;

		if (strncmp(s, SAM_NAMING_REPLY_OK, strlen(SAM_NAMING_REPLY_OK)) == 0) {
			sam_pubkey_t pubkey;

			q = strchr(p, ' ');  /* ' 'VAL.. */
			assert(q != NULL);
			*q = '\0';
			q++;
			q = strchr(q, '=');  /* VALUE= */
			assert(q != NULL);
			q++;
			strlcpy(name, p, sizeof name);
			strlcpy(pubkey, q, sizeof pubkey);
			sam_namingback(name, pubkey, SAM_OK);

		} else if (strncmp(s, SAM_NAMING_REPLY_IK,
				strlen(SAM_NAMING_REPLY_IK)) == 0) {
			q = strchr(p, ' ');  /* ' 'MES.. (optional) */
			if (q != NULL)
				*q = '\0';
			strlcpy(name, p, sizeof name);
			sam_namingback(name, NULL, SAM_INVALID_KEY);

		} else if (strncmp(s, SAM_NAMING_REPLY_KNF,
				strlen(SAM_NAMING_REPLY_KNF)) == 0) {
			q = strchr(p, ' ');  /* ' 'MES.. (optional) */
			if (q != NULL)
				*q = '\0';
			strlcpy(name, p, sizeof name);
			sam_namingback(name, NULL, SAM_KEY_NOT_FOUND);

		} else {
			q = strchr(p, ' ');  /* ' 'MES.. (optional) */
			if (q != NULL)
				*q = '\0';
			strlcpy(name, p, sizeof name);
			sam_namingback(name, NULL, SAM_UNKNOWN);
		}

		return;

	} else if (strncmp(s, SAM_STREAM_CLOSED_REPLY,
			strlen(SAM_STREAM_CLOSED_REPLY)) == 0) {
		char *p;
		sam_sid_t stream_id;

		p = strchr(s, '=');  /* can't use strrchar because of option MESSAGE= */
		assert(p != NULL);  /* ID= */
		p++;
		stream_id = strtol(p, NULL, 10);
		assert(stream_id != 0);
		p = strchr(p, '=');  /* RESULT= */
		assert(p != NULL);
		p++;
		if (strncmp(p, "OK", strlen("OK")) == 0)
			sam_closeback(stream_id, SAM_OK);
		else if (strncmp(p, "CANT_REACH_PEER", strlen("CANT_REACH_PEER")) == 0)
			sam_closeback(stream_id, SAM_CANT_REACH_PEER);
		else if (strncmp(p, "I2P_ERROR", strlen("I2P_ERROR")) == 0)
			sam_closeback(stream_id, SAM_I2P_ERROR);
		else if (strncmp(p, "PEER_NOT_FOUND", strlen("PEER_NOT_FOUND")) == 0)
			sam_closeback(stream_id, SAM_PEER_NOT_FOUND);
		else if (strncmp(p, "TIMEOUT", strlen("TIMEOUT")) == 0)
			sam_closeback(stream_id, SAM_TIMEOUT);
		else
			sam_closeback(stream_id, SAM_UNKNOWN);

		return;

	} else if (strncmp(s, SAM_STREAM_CONNECTED_REPLY,
			strlen(SAM_STREAM_CONNECTED_REPLY)) == 0) {
		char *p;
		sam_sid_t stream_id;
		sam_pubkey_t dest;

		p = strrchr(s, '=');  /* ID= */
		assert(p != NULL);
		*p = '\0';
		p++;
		stream_id = strtol(p, NULL, 10);
		assert(stream_id != 0);
		p = strstr(s, "N=");  /* DESTINATION= */
		p += 2;
		strlcpy(dest, p, sizeof dest);
		sam_connectback(stream_id, dest);
	
		return;

	} else if (strncmp(s, SAM_STREAM_RECEIVED_REPLY,
			strlen(SAM_STREAM_RECEIVED_REPLY)) == 0) {
		char *p;
		sam_sid_t stream_id;
		size_t size;
		void *data;

		p = strrchr(s, '=');  /* SIZE= */
		assert(p != NULL);
		p++;
		size = strtol(p, NULL, 10);
		assert(size != 0);
		p -= 6;
		*p = '\0';
		p = strrchr(s, '=');  /* ID= */
		assert(p != NULL);
		p++;
		stream_id = strtol(p, NULL, 10);
		assert(stream_id != 0);
		data = malloc(size + 1);  /* +1 for NUL termination, so when we are
									receiving a string it will just work and it
									won't be necessary to send NUL.  When binary
									data is sent, the extra NUL character will
									just be ignored by the client program,
									because it is not added to the size */
		if (sam_read2(data, size) != -1) {
			p = data + size;
			*p = '\0';  /* see above NUL note */
			sam_databack(stream_id, data, size);  /* `data' must be freed */
		} else
			free(data);

		return;

	} else if (strncmp(s, SAM_STREAM_STATUS_REPLY,
			strlen(SAM_STREAM_STATUS_REPLY)) == 0) {
		char *p;
		sam_sid_t stream_id;

		p = strchr(s, '=');  /* can't use strrchar because of option MESSAGE= */
		assert(p != NULL);  /* RESULT= */
		p++;
		p = strchr(p, '=');  /* ID= */
		assert(p != NULL);
		p++;
		stream_id = strtol(p, NULL, 10);
		assert(stream_id != 0);
		if (strncmp(s, SAM_STREAM_STATUS_REPLY_OK,
				strlen(SAM_STREAM_STATUS_REPLY_OK)) == 0)
			sam_statusback(stream_id, SAM_OK);
		else if (strncmp(s, SAM_STREAM_STATUS_REPLY_CRP,
				strlen(SAM_STREAM_STATUS_REPLY_CRP)) == 0)
			sam_statusback(stream_id, SAM_CANT_REACH_PEER);
		else if (strncmp(s, SAM_STREAM_STATUS_REPLY_I2E,
				strlen(SAM_STREAM_STATUS_REPLY_I2E)) == 0)
			sam_statusback(stream_id, SAM_I2P_ERROR);
		else if (strncmp(s, SAM_STREAM_STATUS_REPLY_IK,
				strlen(SAM_STREAM_STATUS_REPLY_IK)) == 0)
			sam_statusback(stream_id, SAM_INVALID_KEY);
		else if (strncmp(s, SAM_STREAM_STATUS_REPLY_TO,
				strlen(SAM_STREAM_STATUS_REPLY_TO)) == 0)
			sam_statusback(stream_id, SAM_TIMEOUT);
		else
			sam_statusback(stream_id, SAM_UNKNOWN);

		return;

	} else
		SAMLOG("Unknown SAM command received: %s", s);

	return;
}

/*
 * Reads and callbacks everything in the SAM network buffer until it is clear
 *
 * Returns: true if we read anything, or false if nothing was there
 */
bool sam_read_buffer(void)
{
	bool read_something = false;
	char reply[SAM_REPLY_LEN];

	if (sam_readable()) {
		do {
			sam_read1(reply, SAM_REPLY_LEN);
			read_something = true;
			sam_parse(reply);
		} while (sam_readable());
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
static ssize_t sam_read1(char *buf, size_t n)
{
	size_t nleft;
	ssize_t nread;
	char *p;

	*buf = '\0';  /* this forces `buf' to be a string even if there is a
					 sam_read1 error return */
	if (!samd_connected) {
		SAMLOGS("Cannot read from SAM because the SAM connection is closed");
		sam_diedback();
		return -1;
	}
	assert(n > 0);
	p = buf;
	nleft = n;
	while (nleft > 0) {
		nread = recv(samd, p, 1, 0);
		if (nread == -1) {
			if (errno == EINTR)  /* see Unix Network Pgming vol 1, Sec. 5.9 */
				continue;
			else {
				SAMLOG("recv() failed: %s", strerror(errno));
				sam_close();
				sam_diedback();
				return -1;
			}
		} else if (nread == 0) {  /* EOF */
			SAMLOGS("Connection closed by the SAM host");
			sam_close();
			sam_diedback();
			return -1;
		}
		assert(nread == 1);
		nleft--;
		if (*p == '\n') {  /* end of SAM response */
			*p = '\0';
#if SAM_WIRETAP
			printf("<<<< %s\n", buf);
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
static ssize_t sam_read2(void *buf, size_t n)
{
	size_t nleft;
	ssize_t nread;
	void *p;

	if (!samd_connected) {
		SAMLOGS("Cannot read from SAM because the SAM connection is closed");
		sam_diedback();
		return -1;
	}
	assert(n > 0);
	p = buf;
	nleft = n;
	while (nleft > 0) {
		nread = recv(samd, p, nleft, 0);
		if (nread == -1) {
			if (errno == EINTR)  /* see Unix Network Pgming vol 1, Sec. 5.9 */
				continue;
			else {
				SAMLOG("recv() failed: %s", strerror(errno));
				sam_close();
				sam_diedback();
				return -1;
			}
		} else if (nread == 0) {  /* EOF */
			SAMLOGS("Connection closed by the SAM host");
			sam_close();
			sam_diedback();
			return -1;
		}
		nleft -= nread;
		p += nread;
	}
#if SAM_WIRETAP
	printf("<<<< (read2() %d bytes)\n", n);
#endif
	assert(nleft == 0);/*  <---\                       */
	return n - nleft;  /* should be equal to initial n */
}

/*
 * Checks if there is incoming data waiting to be read from the SAM connection
 *
 * Returns: true if data is waiting, false otherwise
 */
static bool sam_readable(void)
{
	fd_set rset;  /* set of readable descriptors */
	struct timeval tv;
	int rc;

	if (!samd_connected) {
		SAMLOGS("Cannot read from SAM because the SAM connection is closed");
		sam_diedback();
		return false;
	}
	/* it seems like there should be a better way to do this (i.e. not select)*/
	FD_ZERO(&rset);
	FD_SET(samd, &rset);
	tv.tv_sec = 0;
	tv.tv_usec = 10;
	rc = select(samd + 1, &rset, NULL, NULL, &tv);
	if (rc == 0)
		return false;
	else if (rc > 0)
		return true;
	else {
		SAMLOG("select() failed: %s", strerror(errno));
		return false;
	}
}

/*
 * Adds data to the send queue
 *
 * sendq - the send queue
 * data - data to add
 * dsize - the size of the data
 *
 * Returns: true on success, false on error
 */
samerr_t sam_sendq_add(sam_sendq_t *sendq, const void *data, size_t dsize)
{
	assert(dsize >= 0);
	if (dsize == 0) {
		SAMLOGS("dsize is 0 - adding nothing");
		return SAM_OK;
	} else if (sendq->size + dsize > SAM_STREAM_PAYLOAD_MAX) {
		SAMLOGS("The queue size would exceed the maximum SAM payload size -" \
			" will not add to the queue");
		return SAM_TOO_BIG;
	}

	sendq->data = realloc(sendq->data, sendq->size + dsize);
	memcpy(sendq->data + sendq->size, data, dsize);
	sendq->size += dsize;

	return SAM_OK;
}

/*
 * Creates a data queue for use with sam_stream_send
 *
 * Returns: pointer to the newly created send queue
 */
sam_sendq_t *sam_sendq_create(void)
{
	sam_sendq_t *sendq;

	sendq = malloc(sizeof(sam_sendq_t));
	sendq->data = NULL;
	sendq->size = 0;

	return sendq;
}

/*
 * Sends the data in the send queue to the specified stream
 *
 * sendq - the send queue
 * stream_id - stream number to send to
 */
void sam_sendq_send(sam_sendq_t *sendq, sam_sid_t stream_id)
{
	sam_stream_send(stream_id, sendq->data, sendq->size);
	free(sendq);

	return;
}

/*
 * Sends the second SAM handshake command and checks the reply
 *
 * destname - destination name for this program, or "TRANSIENT" to create a
 * 		random temporary destination
 * tunneldepth - length of the I2P tunnels created by this program
 *
 * Returns: SAM error code
 */
static samerr_t sam_session_create(const char *destname, sam_conn_t style,
	uint_t tunneldepth)
{
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
		assert(false);  /* unimplemented */
	}

	sam_write(cmd, strlen(cmd));
	sam_read1(reply, SAM_REPLY_LEN);
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
 * Connects to a remote host and returns a connected descriptor
 *
 * host - host name or ip address
 * port - port number
 *
 * Returns: true on sucess, false on error, with errno set
 */
bool sam_socket_connect(const char *host, uint16_t port)
{
	struct sockaddr_in hostaddr;
	int rc;
	char ipaddr[INET_ADDRSTRLEN];

	samd = socket(AF_INET, SOCK_STREAM, 0);
#ifdef WINSOCK
	if (samd == INVALID_SOCKET)
		return false;
#else
	if (samd == -1)
		return false;
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

	if (connect(samd, (struct sockaddr *)&hostaddr, sizeof hostaddr) == -1)
		return false;

	samd_connected = true;
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
		if (h_errno == TRY_AGAIN) {
#ifdef WINSOCK
			Sleep(1000);
#else
			sleep(1);
#endif
			goto retry;
		} else {
			SAMLOG("DNS resolution failed for %s", hostname);
			errno = ENOENT;
			return false;
		}
	}
	a.s_addr = ((struct in_addr *)h->h_addr)->s_addr;
#ifdef NO_INET_NTOP
	char *tmp;
	tmp = inet_ntoa(a);
	strlcpy(ipaddr, tmp, INET_ADDRSTRLEN);  /* inet_ntoa() was very poorly designed */
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
void sam_stream_close(sam_sid_t stream_id)
{
	char cmd[SAM_CMD_LEN];

#ifdef FAST32_IS_LONG
	snprintf(cmd, sizeof cmd, "STREAM CLOSE ID=%ld\n", stream_id);
#else
	snprintf(cmd, sizeof cmd, "STREAM CLOSE ID=%d\n", stream_id);
#endif
	sam_write(cmd, strlen(cmd));

	return;
}

/*
 * Opens a new stream connection to public key destination `dest'
 *
 * dest - base 64 destination
 *
 * Returns: stream id number
 */
sam_sid_t sam_stream_connect(const sam_pubkey_t dest)
{
	char cmd[SAM_PKCMD_LEN];
	static sam_sid_t id = 0;

	id++;  /* increment the id for the connection */
#ifdef FAST32_IS_LONG
	snprintf(cmd, sizeof cmd, "STREAM CONNECT ID=%ld DESTINATION=%s\n",
		id, dest);
#else
	snprintf(cmd, sizeof cmd, "STREAM CONNECT ID=%d DESTINATION=%s\n",
		id, dest);
#endif
	sam_write(cmd, strlen(cmd));

	return id;
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
samerr_t sam_stream_send(sam_sid_t stream_id, const void *data, size_t size)
{
	char cmd[SAM_CMD_LEN];

	if (size < 1 || size > SAM_STREAM_PAYLOAD_MAX) {
#ifdef NO_Z_FORMAT
		SAMLOG("Invalid data send size (%u bytes) for stream %d",
			size, stream_id);
#else
		SAMLOG("Invalid data send size (%z bytes) for stream %d",
			size, stream_id);
#endif
		return SAM_TOO_BIG;
	}
#ifdef NO_Z_FORMAT
	#ifdef FAST32_IS_LONG
		snprintf(cmd, sizeof cmd, "STREAM SEND ID=%ld SIZE=%u\n",
			stream_id, size);
	#else
		snprintf(cmd, sizeof cmd, "STREAM SEND ID=%d SIZE=%u\n",
			stream_id, size);
	#endif
#else
	snprintf(cmd, sizeof cmd, "STREAM SEND ID=%d SIZE=%z\n",
		stream_id, size);
#endif
	sam_write(cmd, strlen(cmd));
	sam_write(data, size);

	return SAM_OK;
}

/*
 * Converts a SAM error code into a short error message
 *
 * code - the error code
 *
 * Returns: error string
 */
char *sam_strerror(samerr_t code)
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
		case SAM_BAD_VERSION:		/* sam_hello() had an unexpected reply */
			return "Bad SAM version";
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

/*
 * Sends `n' bytes to the SAM host
 *
 * buf - buffer with the data in it
 * n - bytes to send
 *
 * Returns: `n', or -1 on error
 */
static ssize_t sam_write(const void *buf, size_t n)
{
	size_t nleft;
	ssize_t nwritten;
	const char *p;

	if (!samd_connected) {
		SAMLOGS("Cannot write to SAM because the SAM connection is closed");
		sam_diedback();
		return -1;
	}
#if SAM_WIRETAP
	const uchar_t *cp = buf;
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
		nwritten = send(samd, p, nleft, 0);
		if (nwritten <= 0) {
			if (errno == EINTR)  /* see Unix Network Pgming vol 1, Sec. 5.9 */
				continue;
			else {
				SAMLOG("send() failed: %s", strerror(errno));
				sam_close();
				sam_diedback();
				return -1;
			}
		}
		nleft -= nwritten;
		p += nwritten;
	}
#if SAM_WIRETAP
	printf(">>>> (write() %d bytes)\n", n);
#endif

	return n;
}
