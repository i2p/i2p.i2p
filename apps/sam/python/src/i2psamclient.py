#!/usr/bin/env python
#@+leo-ver=4
#@+node:@file python/src/i2psamclient.py
#@@first
"""
Implements a client API for I2CP messaging via SAM

Very simple I2P messaging interface, which should prove easy
to reimplement in your language of choice

This module can be used from cpython or jython

Run this module without arguments to see a demo in action
(requires SAM server to be already running)
"""
#@+others
#@+node:imports
import sys, os, socket, thread, threading, Queue, traceback, StringIO, time

#@-node:imports
#@+node:globals
# -----------------------------------------
# server access settings

i2psamhost = '127.0.0.1'
i2psamport = 7656

# ------------------------------------------
# logging settings

# 1=v.quiet, 2=normal, 3=verbose, 4=debug, 5=painful
verbosity = 5

# change to a filename to log there instead
logfile = sys.stdout

# when set to 1, and when logfile != sys.stdout, log msgs are written
# both to logfile and console stdout
log2console = 1

# don't touch this!
loglock = threading.Lock()

#@-node:globals
#@+node:exceptions
class I2PServerFail(Exception):
    """
    A failure in connecting to the I2CP server
    """

class I2PCommandFail(Exception):
    """
    A failure in an I2CP command
    """
    pass
#@-node:exceptions
#@+node:class I2PSamClient
class I2PSamClient:
    """
    Implements a reference client for accessing I2CP via i2psam
    
    Connects to i2psam's I2PSamServer, sends commands
    and receives results

    The primitives should be reasonably self-explanatory

    Usage summary:
        1. create one or more I2PSamClient instances per process (1 should be fine)
        2. invoke the L{genkeys} method to create destination keypairs
        3. create sessions objects via the L{createSession} method
        4. use these session objects to send and receive data
        5. destroy the session objects when you're done
    
    Refer to the function L{demo} for a simple example
    """
    #@    @+others
    #@+node:attributes
    # server host/port settings exist here in case you might
    # have a reason for overriding in a subclass
    
    host = i2psamhost
    port = i2psamport
    
    i2cpHost = None
    i2cpPort = None
    
    #@-node:attributes
    #@+node:__init__
    def __init__(self, **kw):
        """
        Creates a client connection to i2psam listener
        
        Keywords:
            - host - host to connect to (default 127.0.0.1)
            - port - port to connect to (default 7656)
        """
        # get optional host/port
        log(4, "entered")
    
        self.host = kw.get('host', self.host)
        self.port = int(kw.get('port', self.port))
    
        self.cmdLock = threading.Lock()
    
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    
        self.lockHello = threading.Lock()
        self.sendLock = threading.Lock()
        self.qNewDests = Queue.Queue()
        self.qSession = Queue.Queue()
        self.qDatagrams = Queue.Queue()
        self.qRawMessages = Queue.Queue()
        self.namingReplies = {}
        self.namingCache = {}
        self.isRunning = 1
    
        log(4, "trying connection to SAM server...")
        try:
            self.sock.connect((self.host, self.port))
        except:
            raise I2PServerFail(
                "Connection to i2psam server failed\n"
                "(are you sure your I2P router is running, and\n"
                "listening for I2CP connections on %s:%s?)" % (self.host, self.port)
                )
    
        # fire up receiver thread
        thread.start_new_thread(self.threadRx, ())
    
        # ping the server
        try:
            log(4, "trying to ping SAM server...")
            self.samHello()
        except:
            logException(4, "Exception on handshaking")
            raise I2PServerFail("Failed to handshake with i2psam server")
    
        # connected fine
        log(2, "I2CP Client successfully connected")
    #@-node:__init__
    #@+node:createSession
    def createSession(self, privdest):
        """
        DEPRECATED - use sam* methods instead!
    
        Creates a session using private destkey
        """
        #3. createsession:
        #    - client->server:
        #        - createsession <base64private>\n
        #    - server->client:
        #        - ok\n  OR
        #        - error[ <reason>]\n
    
        self.cmdLock.acquire()
        try:
            self._sendline("createsession %s" % privdest)
            respitems = self._recvline().split(" ", 1)
            if respitems[0] == 'ok':
                res = None
            else:
                res = respitems[1]
        except:
            logException(2, "createsession fail")
            self.cmdLock.release()
            raise
    
        self.cmdLock.release()
    
        if res:
            raise I2PCommandFail("createsession fail: "+res)
    
        return I2PRemoteSession(self, privdest)
    
    #@-node:createSession
    #@+node:destroySession
    def destroySession(self, privdest):
        """
        DEPRECATED - use sam* methods instead!
    
        Destrlys a session using private destkey
        """
        #4. destroysession:
        #    - client->server:
        #        - destroysession <base64private>\n
        #    - server->client:
        #        - ok\n OR
        #        - error[ <reason>]\n
    
        self.cmdLock.acquire()
        try:
            self._sendline("destroysession %s" % privdest)
            respitems = self._recvline().split(" ", 1)
            if respitems[0] == 'ok':
                res = None
            else:
                res = respitems[1]
        except:
            logException(2, "destroysession fail")
            self.cmdLock.release()
            raise
    
        self.cmdLock.release()
    
        if res:
            raise I2PCommandFail("destroysession fail: " + res)
    
        return res
    
    #@-node:destroySession
    #@+node:send
    def send(self, privdest, peerdest, msg):
        """
        DEPRECATED - use sam* methods instead!
    
        Sends a block of data from local dest to remote dest
        """
        #5. send:
        #    - client->server:
        #        - send <size> <localbase64private> <remotebase64dest>\ndata
        #    - server->client:
        #        - ok\n OR
        #        - error[ <reason>]\n
    
        self.cmdLock.acquire()
        try:
            self._sendline("send %s %s %s" % (len(msg), privdest, peerdest))
            self._sendbytes(msg)
            line = self._recvline()
            #print "** %s" % line
            respitems = line.split(" ", 1)
            if respitems[0] == 'ok':
                res = None
            else:
                res = " ".join(respitems[1:])
        except:
            logException(2, "send fail")
            self.cmdLock.release()
            raise
    
        self.cmdLock.release()
    
        if res:
            raise I2PCommandFail("send fail: " + res)
    
        return res
    
    #@-node:send
    #@+node:receive
    def receive(self, privdest):
        """
        DEPRECATED - use sam* methods instead!
    
        receives a block of data, returning string, or None if no data available
        """
        #6. receive:
        #    - client->server:
        #        - receive <localbase64private>\n
        #    - server->client:
        #        - ok <size>\ndata OR
        #        - error[ <reason>]\n
    
        self.cmdLock.acquire()
        try:
            self._sendline("receive %s" % privdest)
            respitems = self._recvline().split(" ", 1)
            if respitems[0] == 'ok':
                res = None
                size = int(respitems[1])
                msg = self._recvbytes(size)
                res = None
            else:
                res = respitems[1]
        except:
            logException(2, "receive fail")
            self.cmdLock.release()
            raise
    
        self.cmdLock.release()
    
        if res:
            raise I2PCommandFail("destroysession fail: " + res)
    
        return msg
    #@-node:receive
    #@+node:samHello
    def samHello(self):
        """
        Sends a quick HELLO PING to SAM server and awaits response
        Arguments:
            - none
    
        Keywords:
            - none
        
        Returns:
            - nothing (None) if ping sent and pong received, or raises an exception if
              failed
        """
        self.lockHello.acquire()
        self.samSend("HELLO", "PING")
        self.lockHello.acquire()
        self.lockHello.release()
    #@-node:samHello
    #@+node:samSessionCreate
    def samSessionCreate(self, style, dest, **kw):
        """
        Creates a SAM session
        
        Arguments:
            - style - one of 'STREAM', 'DATAGRAM' or 'RAW'
            - dest - base64 private destination
        
        Keywords:
            - i2cphost - hostname for the SAM bridge to contact i2p router on
            - i2cpport - port for the SAM bridge to contact i2p router on
        
        Returns:
            - 'OK' if session was created successfully, or a tuple
              (keyword, message) if not
        """
        kw1 = dict(kw)
        kw1['STYLE'] = self.samStyle = style
        kw1['DESTINATION'] = dest
    
        # stick in i2cp host/port if specified
        if kw.has_key('i2cphost'):
            kw1['I2CP.HOST'] = kw['i2cphost']
        if kw.has_key('i2cpport'):
            kw1['I2CP.PORT'] = kw['i2cpport']
        
        self.samSend("SESSION", "CREATE",
                     **kw1)
        subtopic, args = self.qSession.get()
    
        if args['RESULT'] == 'OK':
            return 'OK'
        else:
            return (args['RESULT'], args['MESSAGE'])
    #@-node:samSessionCreate
    #@+node:samDestGenerate
    def samDestGenerate(self):
        """
        Creates a whole new dest and returns an tuple pub, priv as
        base64 public and private destination keys
        """
        self.samSend("DEST", "GENERATE")
        pub, priv = self.qNewDests.get()
        return pub, priv
    #@-node:samDestGenerate
    #@+node:samRawSend
    def samRawSend(self, peerdest, msg):
        """
        Sends a raw anon message to another peer
        
        peerdest is the public base64 destination key of the peer
        """
        self.samSend("RAW", "SEND", msg,
                     DESTINATION=peerdest,
                     )
    #@-node:samRawSend
    #@+node:samRawCheck
    def samRawCheck(self):
        """
        Returns 1 if there are received raw messages available, 0 if not
        """
        return not self.qRawMessages.empty()
    #@-node:samRawCheck
    #@+node:samRawReceive
    def samRawReceive(self, blocking=1):
        """
        Returns the next raw message available,
        blocking if none is available and the blocking arg is set to 0
    
        If blocking is 0, and no messages are available, returns None.
        
        Remember that you can check for availability with
        the .samRawCheck() method
        """
        if not blocking:
            if self.qRawMessages.empty():
                return None
        return self.qRawMessages.get()
        
    #@nonl
    #@-node:samRawReceive
    #@+node:samDatagramSend
    def samDatagramSend(self, peerdest, msg):
        """
        Sends a repliable datagram message to another peer
    
        peerdest is the public base64 destination key of the peer
        """
        self.samSend("DATAGRAM", "SEND", msg,
                     DESTINATION=peerdest,
                     )
    #@-node:samDatagramSend
    #@+node:samDatagramCheck
    def samDatagramCheck(self):
        """
        Returns 1 if there are datagram messages received messages available, 0 if not
        """
        return not self.qDatagrams.empty()
    #@-node:samDatagramCheck
    #@+node:samDatagramReceive
    def samDatagramReceive(self, blocking=1):
        """
        Returns the next datagram message available,
        blocking if none is available.
    
        If blocking is set to 0, and no messages are available,
        returns None.
        
        Remember that you can check for availability with
        the .samRawCheck() method
        
        Returns 2-tuple: dest, msg
        where dest is the base64 destination of the peer from
        whom the message was received
        """
        if not blocking:
            if self.qDatagrams.empty():
                return None
        return self.qDatagrams.get()
    #@-node:samDatagramReceive
    #@+node:samNamingLookup
    def samNamingLookup(self, host):
        """
        Looks up a host in hosts.txt
        """
        # try the cache first
        if self.namingCache.has_key(host):
            log(4, "found host %s in cache" % host)
            return self.namingCache[host]
    
        # make a queue for reply
        q = self.namingReplies[host] = Queue.Queue()
        
        # send off req
        self.samSend("NAMING", "LOOKUP",
                     NAME=host,
                     )
    
        # get resp
        resp = q.get()
    
        result = resp.get('RESULT', 'none')
        if result == 'OK':
            log(4, "adding host %s to cache" % host)
            val = resp['VALUE']
            self.namingCache[host] = val
            return val
        else:
            raise I2PCommandFail("Error looking up '%s': %s %s" % (
                host, result, resp.get('MESSAGE', '')))
    
    #@-node:samNamingLookup
    #@+node:samParse
    def samParse(self, flds):
        """
        carves up a SAM command, returns it as a 3-tuple:
            - cmd - command string
            - subcmd - subcommand string
            - dargs - dict of args
        """
        cmd = flds[0]
        subcmd = flds[1]
        args = flds[2:]
        
        dargs = {}
        for arg in args:
            try:
                name, val = arg.split("=", 1)
            except:
                logException(3, "failed to process %s" % repr(arg))
                raise
            dargs[name] = val
    
        # read and add data if any
        if dargs.has_key('SIZE'):
            size = dargs['SIZE'] = int(dargs['SIZE'])
            dargs['DATA'] = self._recvbytes(size)
    
        #log(4, "\n".join([cmd+" "+subcmd] + [("%s=%s (...)" % (k,v[:40])) for k,v in dargs.items()]))
        log(4, "\n".join([cmd+" "+subcmd] + [("%s=%s (...)" % (k,v)) for k,v in dargs.items()]))
    
        return cmd, subcmd, dargs
    
    
    
    #@-node:samParse
    #@+node:samSend
    def samSend(self, topic, subtopic, data=None, **kw):
        """
        Sends a SAM message (reply?) back to client
        
        Arguments:
            - topic - the first word in the reply, eg 'STREAM'
            - subtopic - the second word of the reply, eg 'CONNECTED'
            - data - a string of raw data to send back (optional)
        Keywords:
            - extra 'name=value' items to pass back.
        
        Notes:
            1. SIZE is not required. If sending back data, it will
               be sized and a SIZE arg inserted automatically.
            2. a dict of values can be passed to the 'args' keyword, in lieu
               of direct keywords. This allows for cases where arg names would
               cause python syntax clashes, eg 'tunnels.depthInbound'
        """
        items = [topic, subtopic]
    
        # stick in SIZE if needed
        if data is not None:
            kw['SIZE'] = str(len(data))
        else:
            data = '' # for later
    
        self.samCreateArgsList(kw, items)
        
        # and whack it together
        buf = " ".join(items) + '\n' + data
    
        # and ship it
        self.sendLock.acquire()
        try:
            self._sendbytes(buf)
        except:
            self.sendLock.release()
            raise
        self.sendLock.release()
    
    #@-node:samSend
    #@+node:samCreateArgsList
    def samCreateArgsList(self, kw1, lst):
        for k,v in kw1.items():
            if k == 'args':
                self.samCreateArgsList(v, lst)
            else:
                lst.append("=".join([str(k), str(v)]))
    #@-node:samCreateArgsList
    #@+node:threadRx
    def threadRx(self):
        """
        Handles all incoming stuff from SAM, storing in
        local queues as appropriate
        """
        while self.isRunning:
            try:
                log(4, "Awaiting next message from server")
                line = self._recvline()
                if line == '':
                    log(3, "I2P server socket closed")
                    return
                flds = line.split(" ")
                topic, subtopic, args = self.samParse(flds)
                log(4, "Got %s %s %s" % (topic, subtopic, args))
                handleMsg = getattr(self, "on_"+topic, None)
                if handleMsg:
                    handleMsg(topic, subtopic, args)
                else:
                    log(2, "No handler for '%s' message" % topic)
            except:
                #logException(3, "Exception handling %s %s\n%s" % (topic, subtopic, args))
                logException(3, "Exception handling %s" % repr(line))
    #@-node:threadRx
    #@+node:on_HELLO
    def on_HELLO(self, topic, subtopic, args):
        """
        Handles HELLO PONG messages from server
        """
        # just wake up the caller
        log(4, "got HELLO")
        self.lockHello.release()
    #@-node:on_HELLO
    #@+node:on_SESSION
    def on_SESSION(self, topic, subtopic, args):
        """
        Handles SESSION messages from server
        """
        # just stick whatever on the queue and wake up the caller
        res = subtopic, args
        self.qSession.put(res)
    #@-node:on_SESSION
    #@+node:on_STREAM
    def on_STREAM(self, topic, subtopic, args):
        """
        Handles STREAM messages from server
        """
    #@-node:on_STREAM
    #@+node:on_DATAGRAM
    def on_DATAGRAM(self, topic, subtopic, args):
        """
        Handles DATAGRAM messages from server
        """
        remdest = args['DESTINATION']
        data = args['DATA']
        
        self.qDatagrams.put((remdest, data))
    #@-node:on_DATAGRAM
    #@+node:on_RAW
    def on_RAW(self, topic, subtopic, args):
        """
        Handles RAW messages from server
        """
        data = args['DATA']
    
        log(3, "Got anonymous datagram %s" % repr(data))
        self.qRawMessages.put(data)
    #@-node:on_RAW
    #@+node:on_NAMING
    def on_NAMING(self, topic, subtopic, args):
        """
        Handles NAMING messages from server
        """
        # just find out hostname, and stick it on resp q
        host = args['NAME']
        self.namingReplies[host].put(args)
    #@-node:on_NAMING
    #@+node:on_DEST
    def on_DEST(self, topic, subtopic, args):
        """
        Handles DEST messages from server
        """
        pubkey = args['PUB']
        privkey = args['PRIV']
        res = pubkey, privkey
        self.qNewDests.put(res)
    #@-node:on_DEST
    #@+node:_recvline
    def _recvline(self):
        """
        Guaranteed read of a full line
        """
        chars = []
        while 1:
            c = self.sock.recv(1)
            if c in ['', '\n']:
                break
            chars.append(c)
        return "".join(chars)
    #@-node:_recvline
    #@+node:_recvbytes
    def _recvbytes(self, num):
        """
        Guaranteed read of num bytes
        """
        if num <= 0:
            return ""
    
        reqd = num
        chunks = []
        while reqd > 0:
            chunk = self.sock.recv(reqd)
            if not chunk:
                raise I2PServerFail("Buffer read fail")
            chunks.append(chunk)
            reqd -= len(chunk)
        return "".join(chunks)
    #@-node:_recvbytes
    #@+node:_sendbytes
    def _sendbytes(self, buf):
        """
        Guaranteed complete send of a buffer
        """
        reqd = len(buf)
        while reqd > 0:
            nsent = self.sock.send(buf)
            if nsent == 0:
                raise I2PServerFail("Send to server failed")
            buf = buf[nsent:]
            reqd -= nsent
    #@-node:_sendbytes
    #@+node:_sendline
    def _sendline(self, line):
        """
        just tacks on a newline and sends
        """
        self._sendbytes(line+"\n")
    #@-node:_sendline
    #@-others
#@-node:class I2PSamClient
#@+node:class I2PRemoteSession
class I2PRemoteSession:
    """
    DEPRECATED

    Wrapper for I2CP connections
    
    Do not instantiate this directly - it gets created by
    I2PSamClient.createSession()
    """    
    #@    @+others
    #@+node:__init__
    def __init__(self, client, dest):
        """
        Do not instantiate this directly
        """
        self.client = client
        self.dest = dest
    #@-node:__init__
    #@+node:send
    def send(self, peerdest, msg):
    
        return self.client.send(self.dest, peerdest, msg)
    #@-node:send
    #@+node:recv
    def receive(self):
        
        return self.client.receive(self.dest)
    #@-node:recv
    #@+node:destroy
    def destroy(self):
        
        return self.client.destroySession(self.dest)
    
    #@-node:destroy
    #@-others
#@-node:class I2PRemoteSession
#@+node:log
def log(level, msg, nPrev=0):

    # ignore messages that are too trivial for chosen verbosity
    if level > verbosity:
        return

    loglock.acquire()
    try:
        # rip the stack
        caller = traceback.extract_stack()[-(2+nPrev)]
        path, line, func = caller[:3]
        path = os.path.split(path)[1]
        full = "%s:%s:%s():\n* %s" % (
            path,
            line,
            func,
            msg.replace("\n", "\n   + "))
        now = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
        msg = "%s %s\n" % (now, full)
    
        if logfile == sys.stdout:
            print msg
        else:
            file(logfile, "a").write(msg+"\n")
    except:
        s = StringIO.StringIO()
        traceback.print_exc(file=s)
        print s.getvalue()
        print "Logger crashed"
    loglock.release()
#@nonl
#@-node:log
#@+node:logException
def logException(level, msg=''):
    s = StringIO.StringIO()
    traceback.print_exc(file=s)
    log(level, "%s\n%s" % (s.getvalue(), msg), 1)
#@-node:logException
#@+node:demoNAMING
def demoNAMING():
    """
    Demonstrates the NAMING service
    """
    print "Starting SAM NAMING demo..."
    print

    print "Instantiating client connection..."
    c0 = I2PSamClient()
    print "Client connection created"

    for host in ['duck.i2p', 'nonexistent.i2p']:
        print "Sending query for host '%s'..." % host
        try:
            res = c0.samNamingLookup(host)
            print "query for %s returned:" % host
            print repr(res)
        except I2PCommandFail, e:
            print "got exception: %s" % repr(e.args)
    
    print
    print "---------------------------------"
    print "NAMING service tests succeeded"
    print "---------------------------------"
    print


#@-node:demoNAMING
#@+node:demoRAW
def demoRAW():
    """
    Runs a demo of SAM RAW messaging
    """
    print "Starting SAM RAW demo..."
    print

    print "Instantiating client connections..."
    c1 = I2PSamClient()
    c2 = I2PSamClient()

    print "Creating dests via SAM"
    pub1, priv1 = c1.samDestGenerate()
    pub2, priv2 = c2.samDestGenerate()
    print "SAM Dests generated ok"
    
    print "Creating SAM RAW SESSION on connection c1..."
    res = c1.samSessionCreate("RAW", priv1)
    if res != 'OK':
        print "Failed to create session on connection c1: %s" % repr(res)
        return
    print "Session on connection c1 created successfully"

    print "Creating SAM SESSION on connection c2..."
    res = c2.samSessionCreate("RAW", priv2)
    if res != 'OK':
        print "Failed to create session on connection c2: %s" % repr(res)
        return
    print "Session on connection c2 created successfully"

    msg = "Hi there!"
    print "sending from c1 to c2: %s" % repr(msg)
    c1.samRawSend(pub2, msg)

    print "now try to receive from c2 (will block)..."
    msg1 = c2.samRawReceive()
    print "Connection c2 got %s" % repr(msg1)

    print
    print "---------------------------------"
    print "RAW data transfer tests succeeded"
    print "---------------------------------"
    print

#@-node:demoRAW
#@+node:demoDATAGRAM
def demoDATAGRAM():
    """
    Runs a demo of SAM DATAGRAM messaging
    """
    print "Starting SAM DATAGRAM demo..."
    print

    print "Instantiating 2 more client connections..."
    c3 = I2PSamClient()
    c4 = I2PSamClient()

    print "Creating more dests via SAM"
    pub3, priv3 = c3.samDestGenerate()
    pub4, priv4 = c4.samDestGenerate()

    print "Creating SAM DATAGRAM SESSION on connection c3..."
    res = c3.samSessionCreate("DATAGRAM", priv3)
    if res != 'OK':
        print "Failed to create DATAGRAM session on connection c3: %s" % repr(res)
        return
    print "DATAGRAM Session on connection c3 created successfully"

    print "Creating SAM DATAGRAM SESSION on connection c4..."
    res = c4.samSessionCreate("DATAGRAM", priv4)
    if res != 'OK':
        print "Failed to create DATAGRAM session on connection c4: %s" % repr(res)
        return
    print "Session on connection c4 created successfully"

    msg = "Hi there, this is a datagram!"
    print "sending from c3 to c4: %s" % repr(msg)
    c3.samDatagramSend(pub4, msg)

    print "now try to receive from c4 (will block)..."
    remdest, msg1 = c4.samDatagramReceive()
    print "Connection c4 got %s from %s..." % (repr(msg1), repr(remdest))


    print
    print "--------------------------------------"
    print "DATAGRAM data transfer tests succeeded"
    print "--------------------------------------"
    print

#@-node:demoDATAGRAM
#@+node:demoSTREAM
def demoSTREAM():
    """
    Runs a demo of SAM STREAM messaging
    """
    print "Starting SAM STREAM demo..."
    print

    print "Instantiating 2 more client connections..."
    c5 = I2PSamClient()
    c6 = I2PSamClient()

    print "Creating more dests via SAM"
    pub5, priv5 = c5.samDestGenerate()
    pub6, priv6 = c6.samDestGenerate()

    print "Creating SAM STREAM SESSION on connection c3..."
    res = c5.samSessionCreate("STREAM", priv5)
    if res != 'OK':
        print "Failed to create STREAM session on connection c5: %s" % repr(res)
        return
    print "STREAM Session on connection c5 created successfully"

    print "Creating SAM STREAM SESSION on connection c6..."
    res = c6.samSessionCreate("STREAM", priv6)
    if res != 'OK':
        print "Failed to create STREAM session on connection c4: %s" % repr(res)
        return
    print "STREAM Session on connection c4 created successfully"

    msg = "Hi there, this is a datagram!"
    print "sending from c5 to c6: %s" % repr(msg)
    c5.samStreamSend(pub6, msg)

    print "now try to receive from c6 (will block)..."
    msg1 = c6.samStreamReceive()
    print "Connection c6 got %s from %s..." % (repr(msg1), repr(remdest))

    print
    print "--------------------------------------"
    print "DATAGRAM data transfer tests succeeded"
    print "--------------------------------------"
    print

#@-node:demoSTREAM
#@+node:demo
def demo():
    """
    This is a simple and straightforward demo of talking to
    the i2psam server socket via the I2PSamClient class.
    
    Read the source, Luke, it's never been so easy...
    """
    print
    print "-----------------------------------------"
    print "Running i2psamclient demo..."
    print "-----------------------------------------"
    print

    demoNAMING()
    demoRAW()
    demoDATAGRAM()
    #demoSTREAM()

    print
    print "-----------------------------------------"
    print "Demo Finished"
    print "-----------------------------------------"

    return
#@-node:demo
#@+node:MAINLINE
if __name__ == '__main__':

    demo()
#@-node:MAINLINE
#@-others

#@-node:@file python/src/i2psamclient.py
#@-leo
