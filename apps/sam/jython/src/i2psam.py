#!/usr/bin/env jython
#@+leo-ver=4
#@+node:@file jython/src/i2psam.py
#@@first
r"""
Implements I2P SAM Server. (refer U{http://drupal.i2p.net/node/view/144})

Also contains useful classes for jython programs,
which wrap the I2P java classes into more python-compatible
paradigms.

If you run this module (or the i2psam.jar file created from it)
without arguments, it'll run an I2P SAM server bridge, listening
on port 7656.

The file i2psamclient.py contains python client classes and a
demo program.

Latest vers of this file is available from U{http://www.freenet.org.nz/i2p/i2psam.py}
Latest epydoc-generated doco at U{http://www.freenet.org.nz/i2p/i2pjyDoc}

The i2psam.jar file is built from this module with the following
command (requires jython and java 1.4.x+ to be installed)::

  CLASSPATH=/path/to/i2p.jar:/path/to/mstreaming.jar \
          jythonc -jar i2psam.jar --all -A net.invisiblenet i2psam.py

"""

#@+others
#@+node:imports
# python imports
import sys, os, time, Queue, thread, threading, StringIO, traceback, getopt
from SocketServer import ThreadingTCPServer, StreamRequestHandler

# java imports
import java

# i2p-specific imports
import net.i2p
import net.i2p.client # to shut up epydoc

# shut up java with a few more imports
import net.i2p.client.streaming
import net.i2p.crypto
import net.i2p.data
import net.i2p.client.I2PClient
import net.i2p.client.I2PClientFactory
import net.i2p.client.naming
#import net.i2p.client.I2PSessionListener

# handy shorthand refs
i2p = net.i2p
jI2PClient = i2p.client.I2PClient

# import my own helper hack module
#import I2PHelper

#@-node:imports
#@+node:globals
clientFactory = i2p.client.I2PClientFactory

#i2phelper = I2PHelper()

PROP_RELIABILITY_BEST_EFFORT = i2p.client.I2PClient.PROP_RELIABILITY_BEST_EFFORT
PROP_RELIABILITY_GUARANTEED = i2p.client.I2PClient.PROP_RELIABILITY_GUARANTEED

version = "0.1.0"

# host/port that our socketserver listens on
i2psamhost = "127.0.0.1"
i2psamport = 7656

# host/port that I2P's I2CP listens on
i2cpHost = "127.0.0.1"
i2cpPort = 7654

#print "i2cpPort=%s" % repr(i2cpPort)

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
#@+node:class JavaWrapper
class JavaWrapper:
    """
    Wraps a java object as attribute '_item', and forwards
    __getattr__ to it.
    
    All the classes here derive from this
    """
    def __init__(self, item):
        self._item = item
    
    def __getattr__(self, attr):
        return getattr(self._item, attr)
    

#@-node:class JavaWrapper
#@+node:class I2PDestination
class I2PDestination(JavaWrapper):
    """
    Wraps java I2P destination objects, with a big difference - these
    objects store the private parts.
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, **kw):
        """
        Versatile constructor
        
        Keywords (choose only one option):
            - (none) - create a whole new dest
            - dest, private - wrap an existing I2P java dest with private stream
              (private is a byte array)
            - bin - reconstitute a public-only dest from a binary string
            - binfile - reconstitute public-only from a binary file
            - binprivate - reconsistitute private dest from binary string
            - binfileprivate - reconsistitute private dest from binary file pathname
            - base64 - reconstitute public-only from base64 string
            - base64file - reconstitute public-only from file containing base64
            - base64private - reconstitute private from string containing base64
            - base64fileprivate - reconstitute private from file containing base64
    
        also:
            - client - a java net.i2p.client.I2PClient object
              (avoids need for temporary client object when creating new dests)
        """
        dest = i2p.data.Destination()
        JavaWrapper.__init__(self, dest)
        self._private = None
    
        if kw.has_key('dest'):
            self._item = kw['dest']
            if kw.has_key('private'):
                self._private = kw['private']
    
        elif kw.has_key('bin'):
            self.fromBin(kw['bin'])
    
        elif kw.has_key('binfile'):
            self.fromBinFilePrivate(kw['binfile'])
    
        elif kw.has_key('binprivate'):
            self.fromBinPrivate(kw['binprivate'])
    
        elif kw.has_key('binfileprivate'):
            self.fromBinFilePrivate(kw['binfileprivate'])
    
        elif kw.has_key('base64'):
            self.fromBase64(kw['base64'])
    
        elif kw.has_key('base64file'):
            self.fromBase64File(kw['base64file'])
        
        elif kw.has_key('base64private'):
            self.fromBase64Private(kw['base64private'])
    
        elif kw.has_key('base64fileprivate'):
            self.fromBase64FilePrivate(kw['base64fileprivate'])
    
        else:
            # create a whole new one, with a temporary client object (if needed)
            if kw.has_key('client'):
                client = kw['client']
            else:
                client = clientFactory.createClient()
            bytestream = java.io.ByteArrayOutputStream()
            self._item = client.createDestination(bytestream)
            self._private = bytestream.toByteArray()
    
    #@-node:__init__
    #@+node:toBin
    def toBin(self):
        """
        Returns a binary string of dest
        """
        return bytearray2str(self.toByteArray())
    
    #@-node:toBin
    #@+node:toBinFile
    def toBinFile(self, path):
        """
        Writes out public binary to a file
        """
        f = open(path, "wb")
        f.write(self.toBin())
        f.flush()
        f.close()
    
    #@-node:toBinFile
    #@+node:toBinPrivate
    def toBinPrivate(self):
        """
        Returns the private key string as binary
        """
        if self._private == None:
            raise NoPrivateKey
        return bytearray2str(self._private)
    
    #@-node:toBinPrivate
    #@+node:toBinFilePrivate
    def toBinFilePrivate(self, path):
        """
        Writes out a binary file with the dest info
        """
        f = open(path, "wb")
        f.write(self.toBinPrivate())
        f.flush()
        f.close()
    
    #@-node:toBinFilePrivate
    #@+node:toBase64
    def toBase64(self):
        """
        Returns base64 string of public part
        """
        return self._item.toBase64()
    
    #@-node:toBase64
    #@+node:toBase64Private
    def toBase64Private(self):
        """
        Exports dest as base64, including private stuff
        """
        if self._private == None:
            raise NoPrivateKey
        return i2p.data.Base64.encode(self._private)
    
    #@-node:toBase64Private
    #@+node:toBase64File
    def toBase64File(self, path):
        """
        Exports dest to file as base64
        """
        f = open(path, "wb")
        f.write(self.toBase64())
        f.flush()
        f.close()
    
    #@-node:toBase64File
    #@+node:toBase64FilePrivate
    def toBase64FilePrivate(self, path):
        """
        Writes out a base64 file with the private dest info
        """
        f = open(path, "wb")
        f.write(self.toBase64Private())
        f.flush()
        f.close()
    
    #@-node:toBase64FilePrivate
    #@+node:fromBin
    def fromBin(self, bin):
        """
        Loads this dest from a binary string
        """
        self._item.fromByteArray(str2bytearray(bin))
        self._private = None
    
    #@-node:fromBin
    #@+node:fromBinFile
    def fromBinFile(self, path):
        """
        Loads public part from file containing binary
        """
        f = open(path, "rb")
        self.fromBin(f.read())
        f.close()
    
    #@-node:fromBinFile
    #@+node:fromBinPrivate
    def fromBinPrivate(self, s):
        """
        Loads this dest object from a base64 private key string
        """
        bytes = str2bytearray(s)
        self._private = bytes
        stream = java.io.ByteArrayInputStream(bytes)
        self._item.readBytes(stream)
    
    #@-node:fromBinPrivate
    #@+node:fromBinFilePrivate
    def fromBinFilePrivate(self, path):
        """
        Loads this dest object, given the pathname of a file containing
        a binary destkey
        """
        self.fromBinPrivate(open(path, "rb").read())
    
    #@-node:fromBinFilePrivate
    #@+node:fromBase64
    def fromBase64(self, b64):
        """
        Loads this dest from a base64 string
        """
        self._item.fromBase64(b64)
        self._private = None
    
    #@-node:fromBase64
    #@+node:fromBase64File
    def fromBase64File(self, path):
        """
        Loads public part from file containing base64
        """
        f = open(path, "rb")
        self.fromBase64(f.read())
        f.close()
    
    #@-node:fromBase64File
    #@+node:fromBase64Private
    def fromBase64Private(self, s):
        """
        Loads this dest object from a base64 private key string
        """
        bytes = i2p.data.Base64.decode(s)
        self._private = bytes
        stream = java.io.ByteArrayInputStream(bytes)
        self._item.readBytes(stream)
    
    #@-node:fromBase64Private
    #@+node:fromBase64PrivateFile
    def fromBase64FilePrivate(self, path):
        """
        Loads this dest from a base64 file containing private key
        """
        self.fromBase64Private(open(path, "rb").read())
    
    #@-node:fromBase64PrivateFile
    #@+node:sign
    def sign(self, s):
        """
        Signs a string using this dest's priv key
        """
        # get byte stream
        bytes = str2bytearray(s)
    
        # stream up our private bytes
        stream = java.io.ByteArrayInputStream(self._private)
    
        # temporary dest object
        d = i2p.data.Destination()
    
        # suck the public part off the stream
        d.readBytes(stream)
    
        # temporary private key object
        privkey = i2p.data.PrivateKey()
        privkey.readBytes(stream)
        
        # now we should just have the signing key portion left in the stream
        signingkey = i2p.data.SigningPrivateKey()
        signingkey.readBytes(stream)
        
        # create DSA engine
        dsa = i2p.crypto.DSAEngine()
        
        sig = dsa.sign(bytes, signingkey)
    
        rawsig = bytearray2str(sig.getData())
    
        return rawsig
    
    #@-node:sign
    #@+node:verify
    def verify(self, s, sig):
        """
        Verifies a string against this dest, to test if it was actually
        signed by whoever has the dest privkey
        """
        # get byte stream from data
        databytes = str2bytearray(s)
    
        # get signature stream from sig
        sigstream = java.io.ByteArrayInputStream(str2bytearray(sig))
    
        # make a signature object
        signature = i2p.data.Signature()
        signature.readBytes(sigstream)
    
        # get signature verify key
        pubkey = self.getSigningPublicKey()
    
    	#log(4, "databytes=%s, pubkey=%s" % (repr(databytes), repr(pubkey)))
        
        # now get a verification
        dsa = i2p.crypto.DSAEngine()
        result = dsa.verifySignature(signature, databytes, pubkey)
    
        return result
    
    
    
    #@-node:verify
    #@+node:hasPrivate
    def hasPrivate(self):
        """
        Returns True if this dest has private parts, False if not
        """
    
        if self._private:
            return 1
        else:
            return 0
    #@-node:hasPrivate
    #@-others

#@-node:class I2PDestination
#@+node:class I2PClient
class I2PClient(JavaWrapper):
    """
    jython-comfortable wrapper for java I2P client class
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, **kw):
        """
        I2PClient constructor
        
        No args or keywords as yet
        """
        client = clientFactory.createClient()
        JavaWrapper.__init__(self, client)
    
    #@-node:__init__
    #@+node:createDestination
    def createDestination(self, **kw):
        """
        Creates a destination, either a new one, or from a bin or base64 file
        
        Keywords:
            - see L{I2PDestination} constructor
        """
        return I2PDestination(**kw)
    
    #@-node:createDestination
    #@+node:createSession
    def createSession(self, dest, sessionClass=None, **kw):
        """
        Create a session
    
        Arguments:
            - dest - an L{I2PDestination} object which MUST contain a private portion
            - sessionClass - if given, this should be a subclass
              of I2PSession. This allows you to implement your own handlers.
    
        Keywords:
            - session options (refer javadocs)
        """
        if sessionClass is None:
            sessionClass = I2PSession
    
        if not dest.hasPrivate():
            raise NoPrivateKey("Dest object has no private key")
    
        #print kw
        #session = self._item.createSession(destStream, dict2props(kw))
        session = sessionClass(client=self, dest=dest, **kw)
        return session
        #return sessionClass(session=session)
    
    #@-node:createSession
    #@-others

#@-node:class I2PClient
#@+node:class I2PSession
class I2PSession(JavaWrapper):
    """
    Wraps an I2P client session

    You can subclass this, overriding the on_* handler callbacks,
    and pass it as an argument to I2PClient.createSession

    In the default 'on_message' callback, message retrieval is
    synchronous - inbound messages get written to an internal queue,
    which you can checked with numMessages() and retrieved from via
    getMessage(). You may override on_message() if you
    want to handle incoming messages asynchronously yourself.

    Note - as far as I can tell, this class should be thread-safe.
    """
    #@    @+others
    #@+node:attributes
    host = i2cpHost
    port = i2cpPort
    #@-node:attributes
    #@+node:__init__
    def __init__(self, **kw):
        """
        I2PSession constructor
    
        Keywords:
            - either:
                - session - a java i2p session object
            - or:
                - client - an L{I2PClient} object
                - dest - an L{I2PDestination} object
        Also:
            - listener - an L{I2PSessionListener} object.
    
        Router-level options:
            - reliability - one of 'guaranteed' and 'besteffort' (default 'besteffort')
            - host - host on which router is running
            - port - port on which router is listening
        """
        #
        # grab options destined for java class
        #
        options = {}
    
        reliability = takeKey(kw, 'reliability', 'besteffort')
        if reliability == 'guaranteed':
            reliability = jI2PClient.PROP_RELIABILITY_GUARANTEED
        else:
            reliability = jI2PClient.PROP_RELIABILITY_BEST_EFFORT
        options[jI2PClient.PROP_RELIABILITY] = reliability
    
        host = takeKey(kw, 'host', self.host)
        options[jI2PClient.PROP_TCP_HOST] = host
    
        port = takeKey(kw, 'port', self.port)
        options[jI2PClient.PROP_TCP_PORT] = str(port)
    
        if kw.has_key('reliability'):
            reliability = kw['reliability']
    
        if kw.has_key('listener'):
            listener = kw['listener']
            del kw['listener']
        else:
            listener = I2PSessionListener()
    
        #print options
    
        #
        # other keywords handled locally
        #
        if kw.has_key('session'):
            session = kw['session']
            del kw['session']
            JavaWrapper.__init__(self, session)
        elif kw.has_key('client') and kw.has_key('dest'):
            client = kw['client']
            dest = kw['dest']
            del kw['client']
            del kw['dest']
            destStream = java.io.ByteArrayInputStream(dest._private)
            session = self._item = client._item.createSession(destStream, dict2props(options))
            #client.createSession(dest, dict2props(options))
        else:
            raise Exception("implementation incomplete")
    
        # set up a listener
        self.setSessionListener(listener)
    
        # set up a queue for inbound msgs
        self.qInbound = Queue.Queue()
        self.lockInbound = threading.Lock()
        self.nInboundMessages = 0
    
        self.lockOutbound = threading.Lock()
    
    
    
    #@-node:__init__
    #@+node:sendMessage
    def sendMessage(self, dest, payload):
        """
        Sends a message to another dest
        
        Arguments:
            - dest - an L{I2PDestination} object
            - payload - a string to send
        """
        dest = dest._item
        payload = str2bytearray(payload)
        self.lockOutbound.acquire()
        try:
            res = self._item.sendMessage(dest, payload)
        except:
            self.lockOutbound.release()
            raise
        self.lockOutbound.release()
        return res
    #@-node:sendMessage
    #@+node:numMessages
    def numMessages(self):
        """
        Returns the number of unretrieved inbound messages
        """
        self.lockInbound.acquire()
        n = self.nInboundMessages
        self.lockInbound.release()
        return n
    #@-node:numMessages
    #@+node:getMessage
    def getMessage(self, blocking=1):
        """
        Returns the next available inbound message.
        
        If blocking is set to 1 (default), blocks
        till another message comes in.
        
        If blocking is set to 0, returns None if there
        are no available messages.
        """
        if blocking:
            msg = self.qInbound.get()
            #print "getMessage: acquiring lock"
            self.lockInbound.acquire()
            #print "getMessage: got lock"
            self.nInboundMessages -= 1
        else:
            #print "getMessage: acquiring lock"
            self.lockInbound.acquire()
            #print "getMessage: got lock"
            if self.nInboundMessages > 0:
                msg = self.qInbound.get()
                self.nInboundMessages -= 1
            else:
                msg = None
        self.lockInbound.release()
        #print "getMessage: released lock"
        return msg
    
    #@-node:getMessage
    #@+node:setSessionListener
    def setSessionListener(self, listener):
        """
        Designates an L{I2PSessionListener} object to listen to this session
        """
        self.listener = listener
        listener.addSession(self)
        self._item.setSessionListener(listener)
    
    
    #@-node:setSessionListener
    #@+node:destroySession
    def destroySession(self):
        """
        Destroys an existing session
    
        Note that due to a jython quirk, calls to destroySession might
        trigger a TypeError relating to arg mismatch - we ignore such
        errors here because by the time the exception happens, the
        session has already been successfully closed
        """
        try:
            self._item.destroySession()
        except TypeError:
            pass
    
    #@-node:destroySession
    #@+node:CALLBACKS
    #
    # handler methods which you should override
    #
    
    #@+others
    #@+node:on_message
    def on_message(self, msg):
        """
        Callback for when a message arrives.
    
        Appends the message to the inbound queue, which you can check
        with the numMessages() method, and read with getMessage()
    
        You should override this if you want to handle inbound messages
        asynchronously.
        
        Arguments:
            - msg - a string that was sent by peer
        """
        #print "on_message: msg=%s" % msg
        self.lockInbound.acquire()
        #print "on_message: got lock"
        self.qInbound.put(msg)
        self.nInboundMessages += 1
        self.lockInbound.release()
        #print "on_message: released lock"
    
    #@-node:on_message
    #@+node:on_abuse
    def on_abuse(self, severity):
        """
        Callback indicating abuse is happening
        
        Arguments:
            - severity - an int of abuse level, 1-100
        """
        print "on_abuse: severity=%s" % severity
    
    #@-node:on_abuse
    #@+node:on_disconnected
    def on_disconnected(self):
        """
        Callback indicating remote peer disconnected
        """
        print "on_disconnected"
    
    #@-node:on_disconnected
    #@+node:on_error
    def on_error(self, message, error):
        """
        Callback indicating an error occurred
        """
        print "on_error: message=%s error=%s" % (message, error)
    
    #@-node:on_error
    #@-others
    #@-node:CALLBACKS
    #@-others
#@-node:class I2PSession
#@+node:class I2PSessionListener
class I2PSessionListener(i2p.client.I2PSessionListener):
    """
    Wraps a java i2p.client.I2PSessionListener object
    """
    def __init__(self, *sessions):
        self.sessions = list(sessions)

    def addSession(self, session):
        """
        Adds an L{I2PSession} object to the list of sessions to listen on
        
        Note - you must also invoke the session's setSessionListener() method
        (see I2PSession.setSessionListener)
        """
        if session not in self.sessions:
            self.sessions.append(session)
    
    def delSession(self, session):
        """
        Stop listening to a given session
        """
        if session in self.sessions:
            del self.sessions.index[session]

    def messageAvailable(self, session, msgId, size):
        """
        Callback from java::
            public void messageAvailable(
                I2PSession session,
                int msgId,
                long size)
        """
        #print "listener - messageAvailable"

        # try to find session in our sessions table
        sessions = filter(lambda s, session=session: s._item == session, self.sessions)
        if sessions:
            #print "compare to self.session->%s" % (session == self.session._item)

            # found a matching session - retrieve it
            session = sessions[0]

            # retrieve message and pass to callback
            msg = session.receiveMessage(msgId)
            msgStr = bytearray2str(msg)
            session.on_message(msgStr)
        else:
            print "messageAvailable: unknown session=%s msgId=%s size=%s" % (session, msgId, size)

    def reportAbuse(self, session, severity):
        """
        Callback from java::
            public void reportAbuse(
                I2PSession session,
                int severity)
        """
        if self.session:
            self.session.on_abuse(severity)
        else:
            print "reportAbuse: unknown session=%s severity=%s" % (session, severity)
    
    def disconnected(self, session):
        """
        Callback from java::
            public void disconnected(I2PSession session)
        """
        if self.session:
            self.session.on_disconnected()
        else:
            print "disconnected: unknown session=%s" % session

    def errorOccurred(session, message, error):
        """
        Callback from java::
            public void errorOccurred(
                I2PSession session,
                java.lang.String message,
                java.lang.Throwable error)
        """
        if self.session:
            self.session.on_error(message, error)
        else:
            print "errorOccurred: message=%s error=%s" % (message, error)

#@-node:class I2PSessionListener
#@+node:class I2PSocket
class I2PSocket:
    """
    Wraps I2P streaming API into a form resembling python sockets
    """
    #@    @+others
    #@+node:attributes
    host = i2cpHost
    port = i2cpPort
    
    #@-node:attributes
    #@+node:__init__
    def __init__(self, dest=None, **kw):
        """
        Create an I2P streaming socket
    
        Arguments:
            - dest - a private destination to associate with this socket
    
        Keywords:
            - host - hostname on which i2cp is listening (default self.host)
            - port - port on which i2cp listens (default self.port)
    
        Internally used keywords (used for wrapping an accept()ed connection):
            - dest
            - remdest
            - sock
            - instream
            - outstream
        """
        # set up null attribs
        self.sockmgr = None
        self.instream = None
        self.outstream = None
        self.sock = None
        self._connected = 0
        self._blocking = 1
    
        # save dest (or lack thereof)
        self.dest = dest
    
        if kw.has_key('sock') \
                and kw.has_key('dest') \
                and kw.has_key('remdest') \
                and kw.has_key('instream') \
                and kw.has_key('outstream'):
            # wrapping an accept()'ed connection
            self.sock = kw['sock']
            self.dest = kw['dest']
            self.remdest = kw['remdest']
            self.instream = kw['instream']
            self.outstream = kw['outstream']
        else:
            # process keywords
            self.host = kw.get('host', self.host)
            self.port = int(kw.get('port', self.port))
    
            # we need a factory, don't we?
            self.sockmgrFact = i2p.client.streaming.I2PSocketManagerFactory()
    #@-node:__init__
    #@+node:bind
    def bind(self, dest=None):
        """
        'binds' the socket to a dest
    
        dest is an I2PDestination object, which you may specify in the constructor
        instead of here. However, we give you the option of specifying here for
        some semantic compatibility with python sockets.
        """
        if dest is not None:
            self.dest = dest
        elif not self.dest:
            # create new dest, client should interrogate it at some time
            self.dest = Destination()
    #@-node:bind
    #@+node:listen
    def listen(self, *args, **kw):
        """
        Sets up the object to receive connections
        """
        # sanity checks
        if self.sockmgr:
            raise I2PSocketError(".sockmgr already present - have you already called listen?")
        if not self.dest:
            raise I2PSocketError("socket is not bound to a destination")
        
        # create the socket manager
        self._createSockmgr()
        
    #@nonl
    #@-node:listen
    #@+node:accept
    def accept(self):
        """
        Waits for incoming connections, and returns a new I2PSocket object
        with the connection
        """
        # sanity check
        if not self.sockmgr:
            raise I2PSocketError(".listen() has not been called on this socket")
    
        # accept a conn and get its streams
        sock = self.sockmgr.getServerSocket().accept()
        instream = sock.getInputStream()
        outstream = sock.getOutputStream()
        remdest = I2PDestination(dest=sock.getPeerDestination())
    
        # wrap it and return it
        sockobj = I2PSocket(dest=self.dest,
                            remdest=remdest,
                            sock=sock,
                            instream=instream,
                            outstream=outstream)
        self._connected = 1
        return sockobj
    
    #@-node:accept
    #@+node:connect
    def connect(self, remdest):
        """
        Connects to a remote destination
        """
        # sanity check
        if self.sockmgr:
            raise I2PSocketError(".sockmgr already present - have you already called listen/connect?")
    
        # create whole new dest if none was provided to constructor
        if self.dest is None:
            self.dest = I2PDestination()
    
        # create the socket manager
        self._createSockmgr()
    
        # do the connect
        #print "remdest._item = %s" % repr(remdest._item)
    
        opts = net.i2p.client.streaming.I2PSocketOptions()
        try:
            self.sock = self.sockmgr.connect(remdest._item, opts)
            self.remdest = remdest
        except:
            logException(2, "apparent exception, continuing...")
        self.instream = self.sock.getInputStream()
        self.outstream = self.sock.getOutputStream()
        self._connected = 1
    #@-node:connect
    #@+node:recv
    def recv(self, nbytes):
        """
        Reads nbytes of data from socket
        """
        # sanity check
        if not self.instream:
            raise I2PSocketError("Socket is not connected")
        
        # for want of better methods, read bytewise
        chars = []
        while nbytes > 0:
            byte = self.instream.read()
            if byte < 0:
                break # got all we're gonna get
            char = chr(byte)
            chars.append(char)
            #print "read: got a byte %s (%s)" % (byte, repr(char))
            nbytes -= 1
            
        # got it all
        buf = "".join(chars)
        #print "recv: buf=%s" % repr(buf)
        return buf
    
    
    #@-node:recv
    #@+node:send
    def send(self, buf):
        """
        Sends buf thru socket
        """
        # sanity check
        if not self.outstream:
            raise I2PSocketError("Socket is not connected")
    
        # and write it out
        #print "send: writing '%s' to outstream..." % repr(buf)
        outstream = self.outstream
        for c in buf:
            outstream.write(ord(c))
    
        # flush just in case
        #print "send: flushing..."
        self.outstream.flush()
    
        #print "send: done"
    #@-node:send
    #@+node:available
    def available(self):
        """
        Returns the number of bytes available for recv()
        """
        return self.sock.available()
    
    #@-node:available
    #@+node:close
    def close(self):
        """
        Closes the socket
        """
        # sanity check
        #if not self._connected:
        #    raise I2PSocketError("Socket is not connected")
    
        # shut up everything
        try:
            self.instream.close()
        except:
            pass
        try:
            self.outstream.close()
        except:
            pass
        try:
            self.sock.close()
        except:
            pass
    #@-node:close
    #@+node:_createSockmgr
    def _createSockmgr(self):
    
        #options = {jI2PClient.PROP_TCP_HOST: self.host,
        #           jI2PClient.PROP_TCP_PORT: self.port}
        options = {}
        props = dict2props(options)
    
        # get a java stream thing from dest
        stream = java.io.ByteArrayInputStream(self.dest._private)
        
        # create socket manager thing
        self.sockmgr = self.sockmgrFact.createManager(stream, self.host, self.port, props)
    #@-node:_createSockmgr
    #@-others
#@-node:class I2PSocket
#@+node:class I2PSamServer
class I2PSamServer(ThreadingTCPServer):
    """
    A server which makes I2CP available via a socket
    """
    #@	@+others
    #@+node:attributes
    host = i2psamhost
    port = i2psamport
    
    i2cphost = i2cpHost
    i2cpport = i2cpPort
    
    version = version
    
    
    #@-node:attributes
    #@+node:__init__
    def __init__(self, i2pclient=None, **kw):
        """
        Create the client listener object
        
        Arguments:
            - i2pclient - an I2PClient object - optional - if not
              given, one will be created
        
        Keywords:
            - host - host to listen on for client conns (default self.host ('127.0.0.1')
            - port - port to listen on for client conns (default self.port (7656)
            - i2cphost - host to talk to i2cp on (default self.i2cphost ('127.0.0.1'))
            - i2cpport - port to talk to i2cp on (default self.i2cphost ('127.0.0.1'))
        """
    
        # create an I2PClient object if none given
        if i2pclient is None:
            i2pclient = I2PClient()
        self.i2pclient = i2pclient
    
        # get optional host/port for client and i2cp
        self.host = kw.get('host', self.host)
        self.port = int(kw.get('port', self.port))
        self.i2cphost = kw.get('i2cphost', self.i2cphost)
        self.i2cpport = int(kw.get('i2cpport', self.i2cpport))
    
        # create record of current sessions, and a lock for it
        self.sessions = {}
        self.sessionsLock = threading.Lock()
        self.streams = {}
        self.streamsLock = threading.Lock()
        self.samNextId = 1
        self.samNextIdLock = threading.Lock()
    
        # and create the server
        try:
            ThreadingTCPServer.__init__(
                self, 
                (self.host, self.port),
                I2PSamClientHandler)
        except:
            log(4, "crashed with host=%s, port=%s" % (self.host, self.port))
            raise
    
    #@-node:__init__
    #@+node:run
    def run(self):
        """
        Run the SAM server.
    
        when connections come in, they are automatically
        accepted, and an L{I2PClientHandler} object created,
        and its L{handle} method invoked.
        """
        log(4, "Listening for client requests on %s:%s" % (self.host, self.port))
        self.serve_forever()
    
    
    #@-node:run
    #@+node:finish_request
    def finish_request(self, request, client_address):
        """Finish one request by instantiating RequestHandlerClass."""
        try:
            self.RequestHandlerClass(request, client_address, self)
        except:
            pass
        log(3, "Client session terminated")
    #@-node:finish_request
    #@+node:samAllocId
    def samAllocId(self):
        """
        Allocates a new unique id as required by SAM protocol
        """
        self.samNextIdLock.acquire()
        id = self.samNextId
        self.samNextId += 1
        self.samNextIdLock.release()
        return id
    #@-node:samAllocId
    #@-others
#@-node:class I2PSamServer
#@+node:class I2PSamClientHandler
class I2PSamClientHandler(StreamRequestHandler):
    r"""
    Manages a single socket connection from a client.
    
    When a client connects to the SAM server, the I2PSamServer
    object creates an instance of this class, and invokes its
    handle method. See L{handle}.

    Note that if a client terminates its connection to the server, the server
    will destroy all current connections initiated by that client
    
    Size values are decimal
    Connection is persistent
    """
    #@	@+others
    #@+node:handle
    def handle(self):
        """
        Reads command/data messages from SAM Client, executes these,
        and sends back responses.
        
        Plants callback hooks into I2PSession objects, so that when
        data arrives via I2P, it can be immediately sent to the client.
        """
        self.localsessions = {}
        self.globalsessions = self.server.sessions
    
        self.localstreams = {} # keyed by sam stream id
        self.globalstreams = self.server.streams
    
        self.samSessionIsOpen = 0
        self.samSessionStyle = ''
    
        # need a local sending lock
        self.sendLock = threading.Lock()
    
        log(5, "Got req from %s" % repr(self.client_address))
    
        try:
            self.namingService = i2p.client.naming.HostsTxtNamingService()
        except:
            logException(2, "Failed to create naming service object")
    
        try:
            while 1:
                # get req
                req = self.rfile.readline().strip()
                flds = [s.strip() for s in req.split(" ")]
                cmd = flds[0]
                if cmd in ['HELLO', 'SESSION', 'STREAM', 'DATAGRAM', 'RAW', 'NAMING', 'DEST']:
                    topic, subtopic, args = self.samParse(flds)
                    method = getattr(self, "on_"+cmd, None)
                    method(topic, subtopic, args)
                else:
                    method = getattr(self, "on_"+cmd, None)
                    if method:
                        method(flds)
                    else:
                        # bad shit
                        self.wfile.write("error unknown command '%s'\n" % cmd)
    
        except IOError:
            log(3, "Client connection terminated")
        except ValueError:
            pass
        except:
            logException(4, "Client req handler crashed")
            self.wfile.write("error\n")
    
        # clean up sessions
        for dest in self.localsessions.keys():
            if dest in self.globalsessions.keys():
                log(4, "forgetting global dest %s" % dest[:30])
                del self.globalsessions[dest]
    
        self.finish()
        #thread.exit()
    
    #@-node:handle
    #@+node:on_genkeys
    def on_genkeys(self, flds):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        # genkeys
        try:
            dest = I2PDestination()
            priv = dest.toBase64Private()
            pub = dest.toBase64()
            write("ok %s %s\n" % (pub, priv))
        except:
            write("error exception\n")
    #@-node:on_genkeys
    #@+node:on_createsession
    def on_createsession(self, flds):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        sessionsLock.acquire()
    
        try:
            b64priv = flds[1]
    
            # spit if someone else already has this dest
            if b64priv in globalsessions.keys():
                write("error dest in use\n")
            elif b64priv in self.localsessions.keys():
                # duh, already open locally, treat as ok
                write("ok\n")
            else:
                # whole new session - set it up
                dest = I2PDestination(base64private=b64priv)
                log(4, "Creating session on dest '%s'" % b64priv[:40])
                session = client.createSession(dest)
                log(4, "Connecting session on dest '%s'" % b64priv[:40])
                session.connect()
                log(4, "Session on dest '%s' now live" % b64priv[:40])
                
                # and remember it
                self.localsessions[b64priv] = session
                globalsessions[b64priv] = session
                
                # and tell the client the good news
                write("ok\n")
        except:
            logException(4, "createsession fail")
            write("error exception\n")
    
        sessionsLock.release()
    #@-node:on_createsession
    #@+node:on_destroysession
    def on_destroysession(self, flds):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        sessionsLock.acquire()
    
        try:
            b64priv = flds[1]
            
            # spit if session not known
            if not globalsessions.has_key(b64priv):
                # no such session presently exists anywhere
                write("error nosuchsession\n")
            elif not self.localsessions.has_key(b64priv):
                # session exists, but another client owns it
                write("error notyoursession\n")
            else:
                # session exists and we own it
                session = self.localsessions[b64priv]
                del self.localsessions[b64priv]
                del globalsessions[b64priv]
                try:
                    session.destroySession()
                    write("ok\n")
                except:
                    raise
        except:
            logException(4, "destroy session failed")
            write("error exception\n")
    
        sessionsLock.release()
    
        log(4, "done")
    
    #@-node:on_destroysession
    #@+node:on_send
    def on_send(self, flds):
    
        #log(4, "entered: %s" % repr(flds))
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        sessionsLock.acquire()
    
        session = None
        try:
            size = int(flds[1])
            b64priv = flds[2]
            b64peer = flds[3]
            msg = self._recvbytes(size)
    
            # spit if session not known
            if not globalsessions.has_key(b64priv):
                # no such session presently exists anywhere
                log(4, "no such session")
                write("error nosuchsession\n")
            elif not self.localsessions.has_key(b64priv):
                # session exists, but another client owns it
                write("error notyoursession\n")
            else:
                session = self.localsessions[b64priv]
        except:
            logException(2, "Send exception")
            write("error exception on send command\n")
    
        sessionsLock.release()
    
        if not session:
            return
        
        # now get/instantiate the remote dest
        try:
            peerDest = I2PDestination(base64=b64peer)
        except:
            peerDest = None
            logException(2, "Send: bad remote dest")
            write("error bad remote dest\n")
        if not peerDest:
            return
    
        # and do the send
        try:
            res = session.sendMessage(peerDest, msg)
        except:
            logException(2, "Send: failed")
            write("error exception on send\n")
            res = None
    
        if res is None:
            return
    
        # report result
        if res:
            write("ok\n")
        else:
            write("error send failed\n")
    
        log(4, "done")
    
    #@-node:on_send
    #@+node:on_receive
    def on_receive(self, flds):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        sessionsLock.acquire()
    
        session = None
        try:
            b64priv = flds[1]
    
            # spit if session not known
            if not globalsessions.has_key(b64priv):
                # no such session presently exists anywhere
                write("error nosuchsession\n")
            elif not self.localsessions.has_key(b64priv):
                # session exists, but another client owns it
                write("error notyoursession\n")
            else:
                session = self.localsessions[b64priv]
        except:
            logException(4, "receive command error")
            write("error exception on receive command\n")
        sessionsLock.release()
    
        if not session:
            log(4, "no session matching privdest %s" % b64priv[:30])
            return
        
        # does this session have any received data?
        if session.numMessages() > 0:
            msg = session.getMessage()
            write("ok %s\n%s" % (len(msg), msg))
        else:
            write("ok 0\n")
    
        log(4, "done")
    
        return
    
    #@-node:on_receive
    #@+node:on_HELLO
    def on_HELLO(self, topic, subtopic, args):
        """
        Responds to client PING
        """
        log(4, "entered")
        self.samSend("HELLO", "PONG")
        log(4, "responded to HELLO")
    
    #@-node:on_HELLO
    #@+node:on_SESSION
    def on_SESSION(self, topic, subtopic, args):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        localsessions = self.localsessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        if subtopic == 'CREATE':
            
            if self.samSessionIsOpen:
                self.samSend("SESSION", "STATUS",
                             RESULT="I2P_ERROR",
                             MESSAGE="Session_already_created",
                             )
                return
    
            # get/validate STYLE arg
            style = self.samSessionStyle = args.get('STYLE', None)
            if style is None:
                self.samSend("SESSION", "STATUS",
                             RESULT="I2P_ERROR",
                             MESSAGE="Missing_STYLE_argument",
                             )
                return
            elif style not in ['STREAM', 'DATAGRAM', 'RAW']:
                self.samSend("SESSION", "STATUS",
                             RESULT="I2P_ERROR",
                             MESSAGE="Invalid_STYLE_argument_'%s'" % style,
                             )
                return
    
            # get/validate DESTINATION arg
            dest = args.get('DESTINATION', None)
            if dest == 'TRANSIENT':
                # create new temporary dest
                dest = self.samDest = I2PDestination()
                destb64 = dest.toBase64Private()
            else:
                # make sure dest isn't globally or locally known
                if dest in globalsessions.keys() or dest in localsessions.keys():
                    self.samSend("SESSION", "STATUS",
                                 RESULT="DUPLICATED_DEST",
                                 MESSAGE="Destination_'%s...'_already_in_use" % dest[:20],
                                 )
                    return
    
                # try to reconstitute dest from given base64
                try:
                    destb64 = dest
                    dest = I2PDestination(base64private=dest)
                except:
                    self.samSend("SESSION", "STATUS",
                                 RESULT="INVALID_KEY",
                                 MESSAGE="Bad_destination_base64_string_'%s...'" % destb64[:20],
                                 )
                    return
    
            # got valid dest now
            self.dest = dest
            self.samDestPub = dest.toBase64()
    
            if style in ['RAW', 'DATAGRAM']:
    
                if style == 'DATAGRAM':
                    # we need to know how big binary pub dests and sigs
                    self.samDestPubBin = dest.toBin()
                    self.samDestPubBinLen = len(self.samDestPubBin)
                    self.samSigLen = len(self.dest.sign("nothing"))
                    
                    log(4, "binary pub dests are %s bytes, sigs are %s bytes" % (
                        self.samDestPubBinLen, self.samSigLen))
    
                i2cpHost = args.get('I2CP.HOST', server.i2cphost)
                i2cpPort = int(args.get('I2CP.PORT', server.i2cpport))
    
                # both these styles require an I2PSession object
                session = client.createSession(dest, host=i2cpHost, port=i2cpPort)
                
                # plug in our inbound message handler
                session.on_message = self.on_message
    
                log(4, "Connecting session on dest '%s'" % destb64[:40])
                try:
                    session.connect()
                except net.i2p.client.I2PSessionException:
                    self.samSend("SESSION", "STATUS",
                                 RESULT="I2P_ERROR",
                                 MESSAGE="Failed_to_connect_to_i2cp_port",
                                 )
                    logException(3, "Failed to connect I2PSession")
                    return
                    
                log(4, "Session on dest '%s' now live" % destb64[:40])
                
                # and remember it
                localsessions[destb64] = session
                globalsessions[destb64] = session
                self.samSession = session
    
            else: # STREAM
                # no need to create session object, because we're using streaming api
    
                # but we do need to mark it as being in use
                localsessions[destb64] = globalsessions[destb64] = None
    
                # make a local socket
                sock = self.samSock = I2PSocket(dest)
    
                # and we also need to fire up a socket listener
                thread.start_new_thread(self.threadSocketListener, (sock, dest))
    
            # finally, we can reply with the good news
            self.samSend("SESSION", "STATUS",
                         RESULT="OK",
                         )
    
        else: # subtopic != CREATE
            self.samSend("SESSION", "STATUS",
                         RESULT="I2P_ERROR",
                         MESSAGE="Invalid_command_'SESSION_%s'" % subtopic,
                         )
            return
    
    #@-node:on_SESSION
    #@+node:on_SESSION_CREATE
    def on_SESSION_CREATE(self, topic, subtopic, args):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        localsessions = self.localsessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
    #@-node:on_SESSION_CREATE
    #@+node:on_STREAM
    def on_STREAM(self, topic, subtopic, args):
    
        log(4, "entered")
    
        server = self.server
        client = server.i2pclient
        globalsessions = server.sessions
        sessionsLock = server.sessionsLock
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
    
        if subtopic == 'CONNECT':
            # who are we connecting to again?
            remdest = I2PDestionation(b64=args['DESTINATION'])
            id = args['ID']
        
            try:
                self.samSock.connect(remdest)
                self.samSend("STREAM", "STATUS",
                             RESULT='OK',
                             ID=id,
                             )
            except:
                self.samSend("STREAM", "STATUS",
                             RESULT='I2P_ERROR',
                             MESSAGE='exception on connect',
                             )
    
    #@-node:on_STREAM
    #@+node:on_DATAGRAM
    def on_DATAGRAM(self, topic, subtopic, args):
        r"""
        DATAGRAM SEND
        DESTINATION=$base64key
        SIZE=$numBytes\n[$numBytes of data]
    
        All datagram messages have a signature/hash header, formatted as:
            - sender's binary public dest
            - S(H(sender_bin_pubdest + recipient_bin_pubdest + msg))
        """
        log(4, "entered")
    
        # at this stage of things, we don't know how to handle anything except SEND
        if subtopic != 'SEND':
            log(3, "Got illegal subtopic '%s' in DATAGRAM command" % subtopic)
            return
    
        # get the details
        peerdestb64 = args['DESTINATION']
        peerdest = I2PDestination(base64=peerdestb64)
        peerdestBin = base64dec(peerdestb64)
        data = args['DATA']
    
        # make up the header
        log(4, "samDestPubBin (%s) %s" % (type(self.samDestPubBin), repr(self.samDestPubBin)))
        log(4, "peerdestBin (%s) %s" % (type(peerdestBin), repr(peerdestBin)))
        log(4, "data (%s) %s" % (type(data), repr(data)))
    
        hashed = shahash(self.samDestPubBin + peerdestBin + data)
        log(4, "hashed=%s" % repr(hashed))
    
        sig = self.dest.sign(hashed)
        log(4, "sig=%s" % repr(sig))
        hdr = self.samDestPubBin + sig
        
        # send the thing
        self.samSession.sendMessage(peerdest, hdr + data)
    
    #@-node:on_DATAGRAM
    #@+node:on_RAW
    def on_RAW(self, topic, subtopic, args):
        r"""
        RAW SEND
        DESTINATION=$base64key
        SIZE=$numBytes\n[$numBytes of data]
        """
        log(4, "entered")
    
        # at this stage of things, we don't know how to handle anything except SEND
        if subtopic != 'SEND':
            return
    
        # get the details
        peerdest = I2PDestination(base64=args['DESTINATION'])
        msg = args['DATA']
    
        # send the thing
        self.samSession.sendMessage(peerdest, msg)
    #@-node:on_RAW
    #@+node:on_NAMING
    def on_NAMING(self, topic, subtopic, args):
    
        log(4, "entered: %s %s %s" % (repr(topic), repr(subtopic), repr(args)))
    
        # at this stage of things, we don't know how to handle anything except LOOKUP
        if subtopic != 'LOOKUP':
            return
    
        # get the details
        host = args['NAME']
    
        log(4, "looking up host %s" % host)
        
        # try to lookup
        jdest = self.namingService.lookup(host)
    
        if not jdest:
            log(4, "host %s not found" % host)
            self.samSend("NAMING", "REPLY",
                         RESULT="KEY_NOT_FOUND",
                         NAME=host,
                         )
            return
    
        try:
            b64 = I2PDestination(dest=jdest).toBase64()
            self.samSend("NAMING", "REPLY",
                         RESULT="OK",
                         NAME=host,
                         VALUE=b64,
                         )
            log(4, "host %s found and valid key returned" % host)
            return
        except:
            log(4, "host %s found but key invalid" % host)
            self.samSend("NAMING", "REPLY",
                         RESULT="INVALID_KEY",
                         NAME=host,
                         )
    
    #@-node:on_NAMING
    #@+node:on_DEST
    def on_DEST(self, topic, subtopic, args):
    
        log(4, "Generating dest")
    
        dest = I2PDestination()
        priv = dest.toBase64Private()
        pub = dest.toBase64()
    
        log(4, "Sending dest to client")
    
        self.samSend("DEST", "REPLY", PUB=pub, PRIV=priv)
    
        log(4, "done")
    #@-node:on_DEST
    #@+node:on_message
    def on_message(self, msg):
        """
        This callback gets plugged into the I2PSession object,
        so we can asychronously notify our client when stuff arrives
        """
        if self.samSessionStyle == 'RAW':
            self.samSend("RAW", "RECEIVE", msg)
    
        elif self.samSessionStyle == 'DATAGRAM':
            # ain't so simple, we gotta rip and validate the header
            remdestBin = msg[:self.samDestPubBinLen]
            log(4, "remdestBin=%s" % repr(remdestBin))
    
            sig = msg[self.samDestPubBinLen:self.samDestPubBinLen+self.samSigLen]
            log(4, "sig=%s" % repr(sig))
    
            data = msg[self.samDestPubBinLen+self.samSigLen:]
            log(4, "data=%s" % repr(data))
            
            # now try to verify
            hashed = shahash(remdestBin + self.samDestPubBin + data)
            log(4, "hashed=%s" % repr(hashed))
    
            remdest = I2PDestination(bin=remdestBin)
            if remdest.verify(hashed, sig):
                # fine - very good, pass it on
                log(4, "sig from peer is valid")
                self.samSend("DATAGRAM", "RECEIVE", data,
                             DESTINATION=remdest.toBase64(),
                             )
            else:
                log(4, "DATAGRAM sig from peer is invalid")
    #@-node:on_message
    #@+node:threadSocketListener
    def threadSocketListener(self, sock, dest):
        """
        Listens for incoming socket connections, and
        notifies the client accordingly
        """
        destb64 = dest.toBase64()
    
        log(4, "Listening for connections to %s..." % destb64[:40])
        while 1:
            newsock = sock.accept()
            
            # need an id, negative
            id = - self.server.samAllocId()
    
            # register it in local and global streams
            self.localstreams[id] = self.globalstreams[id] = newsock
            
            # who is connected to us?
            remdest = newsock.remdest
            remdest_b64 = remdest.toBase64()
            
            # and notify the client
            self.samSend("STREAM", "CONNECTED",
                         DESTINATION=remdest_b64,
                         ID=id)
    #@-node:threadSocketListener
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
    #@+node:_sendbytes
    def _sendbytes(self, raw):
    
        self.wfile.write(raw)
        self.wfile.flush()
    #@-node:_sendbytes
    #@+node:_recvbytes
    def _recvbytes(self, count):
        """
        Does a guaranteed read of n bytes
        """
        read = self.rfile.read
    
        chunks = []
        needed = count
        while needed > 0:
            chunk = read(needed)
            chunklen = len(chunk)
            needed -= chunklen
            chunks.append(chunk)
        raw = "".join(chunks)
    
        # done
        return raw
    
    #@-node:_recvbytes
    #@-others
#@nonl
#@-node:class I2PSamClientHandler
#@+node:Exceptions
class NoPrivateKey(Exception):
    """Destination object has no private key"""

class I2PSocketError(Exception):
    """Error working with I2PSocket objects"""
#@-node:Exceptions
#@+node:shahash
def shahash(s):
    """
    Calculates SHA Hash of a string, as a string, using
    I2P hashing facility
    """
    h = net.i2p.crypto.SHA256Generator().calculateHash(s)
    h = bytearray2str(h.getData())
    return h
#@-node:shahash
#@+node:base64enc
def base64enc(s):
    return net.i2p.data.Base64.encode(s)
#@-node:base64enc
#@+node:base64dec
def base64dec(s):
    return bytearray2str(net.i2p.data.Base64.decode(s))

#@-node:base64dec
#@+node:str2bytearray
def str2bytearray(s):
    """
    Convenience - converts python string to java-friendly byte array
    """
    a = []
    for c in s:
        n = ord(c)
        if n >= 128:
            n = n - 256
        a.append(n)
    return a

#@-node:str2bytearray
#@+node:bytearray2str
def bytearray2str(a):
    """
    Convenience - converts java-friendly byte array to python string
    """
    chars = []
    for n in a:
        if n < 0:
            n += 256
        chars.append(chr(n))
    return "".join(chars)

#@-node:bytearray2str
#@+node:byteoutstream2str
def byteoutstream2str(bs):
    """
    Convenience - converts java-friendly byteoutputstream to python string
    """
    chars = []
    while 1:
        c = bs.read()
        if c >= 0:
            chars.append(chr(c))
        else:
            break
    return "".join(chars)

#@-node:byteoutstream2str
#@+node:dict2props
def dict2props(d):
    """
    Converts a python dict d into a java.util.Properties object
    """
    props = java.util.Properties()
    for k,v in d.items():
        props[k] = str(v)
    return props


#@-node:dict2props
#@+node:takeKey
def takeKey(somedict, keyname, default=None):
    """
    Utility function to destructively read a key from a given dict.
    Same as the dict's 'takeKey' method, except that the key (if found)
    sill be deleted from the dictionary.
    """
    if somedict.has_key(keyname):
        val = somedict[keyname]
        del somedict[keyname]
    else:
        val = default
    return val
#@-node:takeKey
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
#@+node:usage
def usage(detailed=0):
    
    print "Usage: %s <options> [<command>]" % sys.argv[0]
    if not detailed:
        print "Run with '-h' to get detailed help"
        sys.exit(0)

    print "I2PSAM is a bridge that allows I2P client programs to access the"
    print "I2P network by talking over a plaintext socket connection."
    print "References:"
    print "   - http://www.freenet.org.nz/i2p - source, doco, downloadables"
    print "   - http://drupal.i2p.net/node/view/144 - I2P SAM specification"
    print
    print "Options:"
    print "  -h, -?, --help        - display this help"
    print "  -v, --version         - print program version"
    print "  -V, --verbosity=n     - set verbosity to n, default 2, 1==quiet, 4==noisy"
    print "  -H, --listenhost=host - specify host to listen on for client connections"
    print "  -P, --listenport=port - port to listen on for client connections"
    print "      --i2cphost=host   - hostname of I2P router's I2CP interface"
    print "      --i2cpport=port   - port of I2P router's I2CP interface"
    print
    print "Commands:"
    print "     (run with no commands to launch SAM server)"
    print "     samserver - runs as a SAM server"
    print "     test - run a suite of self-tests"
    print
    
    sys.exit(0)



#@-node:usage
#@+node:main
def main():

    argv = sys.argv
    argc = len(argv)

    try:
        opts, args = getopt.getopt(sys.argv[1:],
                                   "h?vV:H:P:",
                                   ['help', 'version', 'verbosity=',
                                    'listenhost=', 'listenport=',
                                    'i2cphost=', 'i2cpport=',
                                    ])
    except:
        traceback.print_exc(file=sys.stdout)
        usage("You entered an invalid option")

    cmd = 'samserver'

    # we prolly should pass all these parms in constructor call, but
    # what the heck!
    #global verbosity, i2psamhost, i2psamport, i2cpHost, i2cpPort
    
    serveropts = {}

    for opt, val in opts:
        if opt in ['-h', '-?', '--help']:
            usage(1)
        elif opt in ['-v', '--version']:
            print "I2P SAM version %s" % version
            sys.exit(0)
        elif opt in ['-V', '--verbosity']:
            serveropts['verbosity'] = int(val)
        elif opt in ['-H', '--listenhost']:
            serveropts['host'] = val
        elif opt in ['-P', '--listenport']:
            serveropts['port'] = int(val)
        elif opt in ['--i2cphost']:
            serveropts['i2cphost'] = val
        elif opt in ['--i2cpport']:
            serveropts['i2cpport'] = int(val)
        else:
            usage(0)

    if len(args) == 0:
        cmd = 'samserver'
    else:
        cmd = args[0]

    if cmd == 'samserver':

        log(2, "Running I2P SAM Server...")
        server = I2PSamServer(**serveropts)
        server.run()

    elif cmd == 'test':
        
        print "RUNNING I2P Jython TESTS"
        testsigs()
        testdests()
        testsession()
        testsocket()

    else:
        usage(0)
#@-node:main
#@+node:testdests
def testdests():
    """
    Demo function which tests out dest generation and import/export
    """
    print
    print "********************************************"
    print "Testing I2P destination create/export/import"
    print "********************************************"
    print

    print "Generating a destination"
    d1 = I2PDestination()

    print "Exporting and importing dest1 in several forms"

    print "public binary string..."
    d1_bin = d1.toBin()
    d2_bin = I2PDestination(bin=d1_bin)

    print "public binary file..."
    d1.toBinFile("temp-d1-bin")
    d2_binfile = I2PDestination(binfile="temp-d1-bin")

    print "private binary string..."
    d1_binprivate = d1.toBinPrivate()
    d2_binprivate = I2PDestination(binprivate=d1_binprivate)

    print "private binary file..."
    d1.toBinFilePrivate("temp-d1-bin-private")
    d2_binfileprivate = I2PDestination(binfileprivate="temp-d1-bin-private")

    print "public base64 string..."
    d1_b64 = d1.toBase64()
    d2_b64 = I2PDestination(base64=d1_b64)

    print "public base64 file..."
    d1.toBase64File("temp-d1-b64")
    d2_b64file = I2PDestination(base64file="temp-d1-b64")

    print "private base64 string..."
    d1_base64private = d1.toBase64Private()
    d2_b64private = I2PDestination(base64private=d1_base64private)

    print "private base64 file..."
    d1.toBase64FilePrivate("temp-d1-b64-private")
    d2_b64fileprivate = I2PDestination(base64fileprivate="temp-d1-b64-private")

    print "All destination creation/import/export tests passed!"


#@-node:testdests
#@+node:testsigs
def testsigs():
    global d1, d1pub, d1sig, d1res
    
    print
    print "********************************************"
    print "Testing I2P dest-based signatures"
    print "********************************************"
    print
    
    print "Creating dest..."
    d1 = I2PDestination()

    s_good = "original stuff that we're signing"
    s_bad = "non-original stuff we're trying to forge"
    
    print "Signing some shit against d1..."
    d1sig = d1.sign(s_good)

    print "Creating public dest d1pub"
    d1pub = I2PDestination(bin=d1.toBin())

    print "Verifying original data with d1pub"
    res = d1pub.verify(s_good, d1sig)
    print "Result: %s (should be 1)" % repr(res)
    
    print "Trying to verify on a different string"
    res1 = d1pub.verify(s_bad, d1sig)
    print "Result: %s (should be 0)" % repr(res1)
    
    if res and not res1:
        print "signing/verifying test passed"
    else:
        print "SIGNING/VERIFYING TEST FAILED"

#@-node:testsigs
#@+node:testsession
def testsession():

    global c, d1, d2, s1, s2

    print
    print "********************************************"
    print "Testing I2P dest->dest messaging"
    print "********************************************"
    print
    
    print "Creating I2P client..."
    c = I2PClient()

    print "Creating destination d1..."
    d1 = c.createDestination()

    print "Creating destination d2..."
    d2 = c.createDestination()

    print "Creating destination d3..."
    d3 = c.createDestination()

    print "Creating session s1 on dest d1..."
    s1 = c.createSession(d1, host='localhost', port=7654)

    print "Creating session s2 on dest d2..."
    s2 = c.createSession(d2)

    print "Connecting session s1..."
    s1.connect()

    print "Connecting session s2..."
    s2.connect()

    print "Sending message from s1 to d2..."
    s1.sendMessage(d2, "Hi there, s2!!")

    print "Retrieving message from s2..."
    print "got: %s" % repr(s2.getMessage())

    print "Sending second message from s1 to d2..."
    s1.sendMessage(d2, "Hi there again, s2!!")

    print "Retrieving message from s2..."
    print "got: %s" % repr(s2.getMessage())

    print "Sending message from s1 to d3 (should take ages then fail)..."
    res = s1.sendMessage(d3, "This is futile!!")
    print "result of that send was %s (should have been 0)" % res

    print "Destroying session s1..."
    s1.destroySession()

    print "Destroying session s2..."
    s2.destroySession()

    print "session tests passed!"
#@-node:testsession
#@+node:testsocket
def testsocket():

    global d1, d2, s1, s2

    print
    print "********************************************"
    print "Testing I2P streaming interface"
    print "********************************************"
    print
    
    print "Creating destinations..."
    dServer = I2PDestination()
    dClient = I2PDestination()

    print "Creating sockets..."
    sServer = I2PSocket(dServer)
    sClient = I2PSocket(dClient)

    # server thread which simply reads a line at a time, then echoes
    # that line back to the client
    def servThread(s):
        print "server: binding socket"
        s.bind()
        print "server: setting socket to listen"
        s.listen()
        print "server: awaiting connection"
        sock = s.accept()
        print "server: got connection"

        sock.send("Hello, echoing...\n")
        buf = ''
        while 1:
            c = sock.recv(1)
            if c == '':
                sock.close()
                print "server: socket closed"
                break

            buf += c
            if c == '\n':
                sock.send("SERVER: "+buf)
                buf = ''

    # client thread which reads lines and prints them to stdout
    def clientThread(s):
        buf = ''
        while 1:
            c = s.recv(1)
            if c == '':
                s.close()
                print "client: socket closed"
                break
            buf += c
            if c == '\n':
                print "client: got %s" % repr(buf)
                buf = ''

    print "launching server thread..."
    thread.start_new_thread(servThread, (sServer,))

    print "client: trying to connect"
    sClient.connect(dServer)

    print "client: connected, launching rx thread"
    thread.start_new_thread(clientThread, (sClient,))

    while 1:
        line = raw_input("Enter something (q to quit)> ")
        if line == 'q':
            print "closing client socket"
            sClient.close()
            break
        sClient.send(line+"\n")

    print "I2PSocket test apparently succeeded"

#@-node:testsocket
#@+node:MAINLINE
if __name__ == '__main__':
    main()

#@-node:MAINLINE
#@-others


#@-node:@file jython/src/i2psam.py
#@-leo
