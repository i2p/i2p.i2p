#! /usr/bin/env python
#@+leo-ver=4
#@+node:@file stasher.py
#@@first
"""
A simple implementation of the
U{Kademlia<http://www.infoanarchy.org/wiki/wiki.pl?Kademlia>}
P2P distributed storage and retrieval protocol, designed to
utilise the U{I2P<http://www.i2p.net>} stealth network as its transport.

Most application developers will only need to know about the L{KNode} class
"""

# I strongly recommend that when editing this file, you use the Leo
# outlining and literate programming editor - http://leo.sf.net
# If Leo doesn't agree with your religion, please try to leave the markups intact

#@+others
#@+node:explanatory comments
#@+at
# Tech overview:
#     - this implementation creates each Node ID as an SHA1 hash of
#       the node's 'destination' - the string which constitutes its
#       address as an I2P endpoint.
# 
# Datagram formats:
#     - each datagram sent from one node to another is a python dict object,
#       encoded and decoded with the 'bencode' object serialisation module.
#     - we use bencode because regular Python pickle is highly insecure,
#       allowing crackers to create malformed pickles which can have all
#       manner of detrimental effects, including execution of arbitrary code.
#     - the possible messages are listed below, along with their consituent
#       dictionary keys:
#           1. ping:
#               - msgId - a message identifier guaranteed to be unique
#                 with respect to the sending node
#           2. findNode:
#               - msgId - unique message identifier
#               - hash - the hash we're looking for
#               - initiator - True/False, according to whether this node
#                 should initiate/perform the findNode, or whether this
#                 rpc is coming from another seeking node
#           3. findData:
#               - msgId - unique message identifier
#               - hash - the exact key hash of the data we want to retrieve
#               - initiator - True/False, according to whether this node
#                 should initiate/perform the findNode, or whether this
#                 rpc is coming from another seeking node
#           4. store:
#               - msgId - unique message identifier
#               - hash - the exact key hash of the data we want to store
#               - data - the data we want to store
#           5. reply:
#               - msgId - the original msgId we're replying to
#              The other items in a reply message depend on what kind
#              of message we're replying to, listed below:
#                     1. ping - no additional data
#                     2. findNode:
#                         - nodes - a list of dests nearest the given hash
#                     3. findData:
#                         - nodes - as for findNode, OR
#                         - data - the retrieved data, or None if not found
#                     4. store:
#                         - status - True or False according to whether
#                           the store operation was successful
#@-at
#@-node:explanatory comments
#@+node:imports
import sys, os, types, sha, random, threading, thread, traceback, Queue
import time, math, random, pickle, getopt, re
import signal

# some windows-specifics (yggghh)
if sys.platform == 'win32':
    try:
        import win32api
        import win32process
        import _winreg
    except:
        print "Python win32 extensions not installed."
        print "Please go to http://sourceforge.net/project/showfiles.php?group_id=78018"
        print "and download/install the file pywin32-202.win32-py%s.%s.exe" % \
            sys.version_info[:2]
        sys.exit(1)

from StringIO import StringIO
from pdb import set_trace

try:
    import bencode
except:
    print "The bencode module is missing from your python installation."
    print "Are you sure you installed Stasher correctly?"
    sys.exit(1)

try:
    import i2p.socket
    import i2p.select
    import i2p.pylib
    SocketServer = i2p.pylib.SocketServer
    socket = i2p.pylib.socket
except:
    print "You don't appear to have the I2P Python modules installed."
    print "Not good. Stasher totally needs them."
    print "Please to to i2p/apps/sam/python in your I2P cvs tree, and"
    print "install the core I2P python modules first"
    sys.exit(1)

#@-node:imports
#@+node:constants

# --------------------------------------------
# START USER-CONFIGURABLE CONSTANTS
# --------------------------------------------

# host:port to connect to I2P SAM Bridge
samAddr = i2p.socket.samaddr

# host:port to listen on for command line client
clientAddr = "127.0.0.1:7659"

defaultNodename = "0" # will be prefixed by 'stashernode'

# maximum size of each stored item
maxValueSize = 30000

# maximum number of noderefs that can be stored in a bucket
# (refer spec section 2.1, first paragraph)
maxBucketSize = 20

# number of peers to return from a search
numSearchPeers = 3

# maximum number of concurrent queries per findnode/finddata rpc
maxConcurrentQueries = 10

# number of peers to store onto
numStorePeers = 10

# Logger settings
logFile = None
logVerbosity = 2

# data directory location - set to a path to override the default
# which is the user's home dir
dataDir = None

# whether a node, on startup, should do a findnode on itself to
# locate its closest neighbours
greetPeersOnStartup = False
#greetPeersOnStartup = True

# multi-purpose testing flag
testing = False
#testing = True

tunnelDepth = 0

# set to True to enable single handler thread that manages all nodes,
# or False to make each node run its own handler thread
#runCore = False
runCore = True

# timeouts - calibrate as needed
timeout = {
    'ping' : 60,
    'findNode' : 60,
    'findData' : 60,
    'store' : 60,
    }

logToSocket = None

desperatelyDebugging = False

if desperatelyDebugging:
    runCoreInBackground = False
else:
    runCoreInBackground = True

# --------------------------------------------
# END OF USER-CONFIGURABLE CONSTANTS
# --------------------------------------------

# ----------------------------------------------
# hack anything below this line at your own risk

#@-node:constants
#@+node:globals
# keep a dict of existing nodes, so we can prevent
# client progs from creating 2 nodes of the same name
_nodes = {}

version = "0.0.1"

#@-node:globals
#@+node:Exceptions
# define our exceptions

class KValueTooLarge(Exception):
    """
    Trying to insert a value of excessive size into the network.
    Maximum key size is L{maxValueSize}
    """

class KBadHash(Exception):
    """
    Invalid hash string
    """

class KNotImplemented(Exception):
    """
    A required method was not implemented
    """

class KBadNode(Exception):
    """
    Invalid Node object
    """

class KBadPeer(Exception):
    """
    Invalid Peer object - should be a KPeer
    """

class KBadDest(Exception):
    """Invalid I2P Node Dest"""

#@-node:Exceptions
#@+node:Mixins
#@+node:class KBase
class KBase:
    """
    A mixin which adds a class-specific logger
    """
    def log(self, verbosity, msg):
        
        log(verbosity, msg, 1, self.__class__.__name__)

    def logexc(self, verbosity, msg):

        logexc(verbosity, msg, 1, self.__class__.__name__)

#@-node:class KBase
#@-node:Mixins
#@+node:Main Engine
#@+node:class KCore
class KCore(KBase):
    """
    Singleton class which performs all the needed background processing.
    
    By scheduling all processing through this object, we eliminate the
    need to create threads on a per-node basis, and also make this thing
    far easier to debug.

    The core launches only two background threads:
        - L{threadRxPackets} - listen for incoming packets bound for
          any node running within a single process
        - L{threadHousekeeping} - periodically invoke maintenance methods
          of each node, so the node can check for timeout conditions and
          other untoward happenings

    These threads start up when the first node in this process is created,
    and stop when the last node ceases to exist.

    Upon first import, the L{stasher} module creates one instance of this
    class. Upon creation, L{KNode} objects register themselves with this core.
    """
    #@    @+others
    #@+node:attributes
    #@-node:attributes
    #@+node:__init__
    def __init__(self, bg=True):
        """
        Creates the I2P Kademlia core object
        """
        self.bg = bg
        self.fg = False
    
        # subscribed nodes
        self.nodes = []
        #self.nodesLock = threading.Lock()
        
        self.isRunning = False
        self.isRunning_rx = False
    
    #@-node:__init__
    #@+node:subscribe
    def subscribe(self, node):
        """
        Called by a node to 'subscribe' for background processing
        If this is the first node, starts the handler thread
        """
        #self.nodesLock.acquire()
        try:
            nodes = self.nodes
            
            if node in nodes:
                self.log(2, "duhhh! node already subscribed" % repr(node))
                return
        
            nodes.append(node)
            
            if not self.isRunning:
                self.isRunning = True
                if self.bg and not self.fg:
                    self.log(3, "First node subscribing, launching threads")
                    thread.start_new_thread(self.threadRxPackets, ())
                    thread.start_new_thread(self.threadHousekeeping, ())
        except:
            traceback.print_exc()
            self.log(2, "exception")
    
        #self.nodesLock.release()
    
    #@-node:subscribe
    #@+node:unsubscribe
    def unsubscribe(self, node):
        """
        Unsubscribes a node from the core
        
        If this was the last node, stops the handler thread
        """
        #self.nodesLock.acquire()
        try:
            nodes = self.nodes
            
            if node not in nodes:
                self.log(4, "duhhh! node %s was not subscribed" % repr(node))
                return
    
            self.log(2, "trying to unsubscribe node %s" % node.name)
            nodes.remove(node)
    
            if len(nodes) == 0:
                self.isRunning = False
        except:
            traceback.print_exc()
            self.log(2, "exception")
    
        #self.nodesLock.release()
    
    #@-node:unsubscribe
    #@+node:threadRxPackets
    def threadRxPackets(self):
        """
        Sits on a select() loop, processing incoming datagrams
        and actioning them appropriately.
        """
        self.isRunning_rx = True
        self.log(3, "KCore packet receiver thread running")
        try:
            while self.isRunning:
                socks = [node.sock for node in self.nodes]
                if desperatelyDebugging:
                    set_trace()
                try:
                    inlist, outlist, errlist = self.select(socks, [], [], 1)
                except KeyboardInterrupt:
                    self.isRunning = 0
                    return
    
                self.log(5, "\ninlist=%s" % repr(inlist))
                if inlist:
                    self.log(5, "got one or more sockets with inbound data")
                    #self.nodesLock.acquire()
                    for sock in inlist:
                        node = self.nodeWhichOwnsSock(sock)
                        if node != None:
                            node._doRx()
                    #self.nodesLock.release()
    
                elif self.fg:
                    return
    
                else:
                    time.sleep(0.1)
        except:
            #self.nodesLock.release()
            traceback.print_exc()
            self.log(1, "core handler thread crashed")
        self.isRunning_rx = False
        self.log(3, "core handler thread terminated")
    
    #@-node:threadRxPackets
    #@+node:threadHousekeeping
    def threadHousekeeping(self):
        """
        Periodically invoke nodes' housekeeping
        """
        self.log(3, "\nnode housekeeping thread running")
        try:
            while self.isRunning:
                #self.log(4, "calling nodes' housekeeping methods")
                #self.nodesLock.acquire()
                for node in self.nodes:
                    node._doHousekeeping()
                #self.nodesLock.release()
                time.sleep(1)
            self.log(3, "\nnode housekeeping thread terminated")
        except:
            #self.nodesLock.release()
            traceback.print_exc()
            self.log(1, "\nnode housekeeping thread crashed")
    
    #@-node:threadHousekeeping
    #@+node:nodeWhichOwnsSock
    def nodeWhichOwnsSock(self, sock):
        """
        returns ref to node which owns a socket
        """
        for node in self.nodes:
            if node.sock == sock:
                return node
        return None
    #@-node:nodeWhichOwnsSock
    #@+node:cycle
    def cycle(self):
    
        self.fg = True
        self.threadRxPackets()
    
    #@-node:cycle
    #@+node:run
    def run(self, func=None):
        """
        Runs the core in foreground, with the client func in background
        """
        if func==None:
            func = test
    
        self.bg = False
    
        thread.start_new_thread(self.runClient, (func,))
        
        set_trace()
    
        self.threadRxPackets()
    
    #@-node:run
    #@+node:stop
    def stop(self):
        self.isRunning = False
    
    #@-node:stop
    #@+node:runClient
    def runClient(self, func):
    
        self.log(3, "Core: running client func")
        try:
            func()
        except:
            traceback.print_exc()
        self.log(3, "Core: client func exited")
        self.stop()
    #@-node:runClient
    #@+node:select
    def select(self, inlist, outlist, errlist, timeout):
        
        return i2p.select.select(inlist, outlist, errlist, timeout)
    
    #@-node:select
    #@-others

#@-node:class KCore
#@+node:create instance
# create an instance of _KCore
core = KCore()

#@-node:create instance
#@-node:Main Engine
#@+node:Basic Classes
#@+node:Node-local Storage
#@+node:class KStorageBase
class KStorageBase(KBase):
    """
    Base class for node storage objects

    This needs to be overridden by implementation-specific
    solutions.
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, node, *args, **kw):
        """
        Override this method
        
        First argument should be a node instance
        """
        raise KNotImplemented
    
    #@-node:__init__
    #@+node:putRefs
    def putRefs(self, *refs):
        """
        Saves one or more noderefs
        
        Arguments:
            - zero or more KPeer objects, or lists or tuples of objects
        """
        raise KNotImplemented
    #@-node:putRefs
    #@+node:getRefs
    def getRefs(self):
        """
        Returns a list of KPeer objects, comprising refs
        of peers known to this node
        """
        raise KNotImplemented
    
    #@-node:getRefs
    #@+node:putKey
    def putKey(self, key, value):
        """
        Stores value, a string, into the local storage
        under key 'key'
        """
        raise KNotImplemented
    
    #@-node:putKey
    #@+node:getKey
    def getKey(self, key):
        """
        Attempts to retrieve item from node's local, which was
        stored with key 'key'.
        
        Returns value as a string if found, or None if not present
        """
        raise KNotImplemented
    #@-node:getKey
    #@+node:private methods
    #@+others
    #@+node:_expandRefsList
    def _expandRefsList(self, args, lst=None):
        """
        Takes a sequence of args, each of which can be a KPeer
        object, or a list or tuple of KPeer objects, and expands
        this into a flat list
        """
        if lst == None:
            lst = []
        for item in args:
            if type(item) in [type(()), type([])]:
                self._expandRefsList(item, lst)
            else:
                lst.append(item)
        return lst
    
    #@-node:_expandRefsList
    #@-others
    #@-node:private methods
    #@-others
#@-node:class KStorageBase
#@+node:class KStorageFile
class KStorageFile(KStorageBase):
    """
    Implements node-local storage, using the local filesystem,
    with the following hierarchy:
        
        - HOME ( ~ in linux, some other shit for windows)
           - .i2pkademlia
               - <nodename>
                   - noderefs
                       - <node1 base64 hash>
                           - contains node dest, and other shit
                       - ...
                   - keys
                       - <keyname1>
                           - contains raw key value
                       - ...

    This is one ugly sukka, perhaps a db4, mysql etc implementation
    would be better.
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, node, storeDir=None):
        """
        Creates a persistent storage object for node
        'nodeName', based at directory 'storeDir' (default
        is nodeDir
        """
        self.node = node
        self.nodeName = node.name
    
        if storeDir == None:
            # work out local directory
            self.topDir = userI2PDir()
    
        # add node dir and subdirs
        self.nodeDir = userI2PDir(self.nodeName)
        
        self.refsDir = os.path.join(self.nodeDir, "noderefs")
        if not os.path.isdir(self.refsDir):
            os.makedirs(self.refsDir)
    
        self.keysDir = os.path.join(self.nodeDir, "keys")
        if not os.path.isdir(self.keysDir):
            os.makedirs(self.keysDir)
    
    #@-node:__init__
    #@+node:putRefs
    def putRefs(self, *args):
        """
        Saves one or more noderefs into filesystem
        
        Arguments:
            - zero or more KPeer objects, or lists or tuples of objects
        """
        lst = self._expandRefsList(args)
        for item in lst:
            b64hash = shahash(item.dest)
            itemPath = os.path.join(self.refsDir, b64hash)
            itemDict = {'dest':item.dest} # might need to expand later
            itemPickle = bencode.bencode(itemDict)
            file(itemPath, "wb").write(itemPickle)
        pass
    #@-node:putRefs
    #@+node:getRefs
    def getRefs(self):
        """
        Returns a list of KPeer objects, comprising refs
        of peers known to this node
    
        These are read from the directory self.refsDir.
        Any that can't be unpickled and instantiated are dropped, but logged
        """
        peers = []
        for f in os.listdir(self.refsDir):
    
            path = os.path.join(self.refsDir, f)
            pickled = file(path, "rb").read()
            try:
                d = bencode.bdecode(pickled)
            except:
                self.log(3, "node %s, bad pickle ref file %s" % (
                    self.nodeName, f))
                continue
            
            # instantiate a peer object
            try:
                peer = KPeer(self.node, d['dest'])
            except:
                self.log(3, "node %s, bad unpickled ref file %s" % (
                    self.nodeName, f))
                continue
    
            # success
            peers.append(peer)
    
        return peers
    
    #@-node:getRefs
    #@+node:putKey
    def putKey(self, key, val, keyIsHashed=False):
        """
        Stores a string into this storage under the key 'key'
    
        Returns True if key was saved successfully, False if not
        """
        try:
            if keyIsHashed:
                keyHashed = key
            else:
                keyHashed = shahash(key)
            keyHashed = keyHashed.lower()
            keyPath = os.path.join(self.keysDir, keyHashed)
            file(keyPath, "wb").write(val)
            self.log(4, "stored key: '%s'\nunder hash '%s'\n(keyIsHashed=%s)" % (
                key, keyHashed, keyIsHashed))
            return True
        except:
            traceback.print_exc()
            self.log(3, "failed to store key")
            return False
    
    #@-node:putKey
    #@+node:getKey
    def getKey(self, key, keyIsHashed=False):
        """
        Attempts to retrieve item from node's local file storage, which was
        stored with key 'key'.
        
        Returns value as a string if found, or None if not present
        """
        try:
            if keyIsHashed:
                keyHashed = key
            else:
                keyHashed = shahash(key)
            
            keyHashed = keyHashed.lower()
            self.log(4, "key=%s, keyHashed=%s, keyIsHashed=%s" % (key, keyHashed, keyIsHashed))
    
            keyPath = os.path.join(self.keysDir, keyHashed)
        
            if os.path.isfile(keyPath):
                return file(keyPath, "rb").read()
            else:
                return None
        except:
            traceback.print_exc()
            self.log(3, "error retrieving key '%s'" % key)
            return None
    
    #@-node:getKey
    #@-others
#@-node:class KStorageFile
#@-node:Node-local Storage
#@+node:class KHash
class KHash(KBase):
    """
    Wraps 160-bit hashes as abstract objects, on which
    operations such as xor, <, >, etc can be performed.
    
    Kademlia node ids and keys are held as objects
    of this class.

    Internally, hashes are stored as python long ints
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, val=None, **kw):
        """
        Create a new hash object.
        
        val can be one of the following:
            - None (default) - a random value will be created
            - long int - this will be used as the raw hash
            - string - the string will be hashed and stored
            - another KHash object - its value will be taken
            - a KNode or KPeer object - its hash will be taken
    
        If val is not given, a raw hash value can be passed in
        with the keyword 'raw'. Such value must be a python long int
        or a 20-char string
        """
        self.value = 0L
        if val:
            if isinstance(val, KHash):
                self.value = val.value
            elif type(val) in [type(0), type(0L)]:
                self.value = long(val)
            elif isinstance(val, KNode) or isinstance(val, KPeer):
                self.value = val.id.value
            else:
                raw = self.raw = shahash(val, bin=1)
                for c in raw:
                    self.value = self.value * 256 + ord(c)
        else:
            rawval = kw.get('raw', None)
            if rawval == None:
                # generate random
                random.seed()
                for i in range(20):
                    self.value = self.value * 256 + random.randint(0, 256)
            elif type(rawval) in [type(0), type(0L)]:
                self.value = long(rawval)
            elif type(rawval) == type(""):
                if len(rawval) == 20:
                    for i in rawval:
                        self.value = self.value * 256 + ord(i)
                elif len(rawval) == 40:
                    try:
                        self.value = long(rawval, 16)
                    except:
                        raise KBadHash(rawval)
                else:
                    raise KBadHash(rawval)
            else:
                print "rawval=%s %s %s" % (type(rawval), rawval.__class__, repr(rawval))
                raise KBadHash(rawval)
    
    #@-node:__init__
    #@+node:__str__
    def __str__(self):
        return "<KHash: 0x%x>" % self.value
    
    def __repr__(self):
        return str(self)
    
    #@-node:__str__
    #@+node:asHex
    def asHex(self):
        return ("%040x" % self.value).lower()
    
    #@-node:asHex
    #@+node:distance
    def distance(self, other):
        """
        calculates the 'distance' between this hash and another hash,
        and returns it as i (where distance = 2^i, and 0 <= i < 160)
        """
    
        #log(4, "comparing: %s\nwith %s" % (self.value, other.value))
    
        rawdistance = self.value ^ other.value
        if not rawdistance:
            return 0
    
        return int(math.log(rawdistance, 2))
    
    #@-node:distance
    #@+node:rawdistance
    def rawdistance(self, other):
        """
        calculates the 'distance' between this hash and another hash,
        and returns it raw as this xor other
        """
        return self.value ^ other.value
    
    #@-node:rawdistance
    #@+node:operators
    def __eq__(self, other):
        #log(2, "KHash: comparing %s to %s" % (self, other))
        res = self.value == getattr(other, 'value', None)
        #self.log(2, "KHash: res = %s" % repr(res))
        return res
    
    def __ne__(self, other):
        return not (self == other)
    
    def __lt__(self, other):
        return self.value < other.value
    
    def __gt__(self, other):
        return self.value > other.value
        
    def __le__(self, other):
        return self.value <= other.value
    
    def __ge__(self, other):
        return self.value >= other.value
    
    def __ne__(self, other):
        return self.value != other.value
    
    def __xor__(self, other):
        return self.value ^ other.value
    
    #@-node:operators
    #@-others
#@-node:class KHash
#@+node:class KBucket
class KBucket(KBase):
    """
    Implements the 'k-bucket' object as required in Kademlia spec
    """
    #@    @+others
    #@+node:__init__
    def __init__(self):
        """
        Creates a single k-bucket
        """
        # list of known nodes
        # order is least recently seen at head, most recently seen at tail
        self.nodes = []
    
        # list of death-row records
        # refer spec section 2.1, paragraph 2
        # we deviate a little:
        #   when we hear from a new peer, and the bucket is full,
        #   we temporarily displace the old peer, and stick the new
        #   peer at end of list, then send out a ping
        #   If we hear from the old peer within a reasonable time,
        #   the new peer gets evicted and replaced with the old peer
        #
        # this list holds 2-tuples (oldpeer, newpeer), where
        # oldpeer is the least-recently-seen peer that we displaced, and
        # newpeer is the new peer we just heard from.
        self.deathrow = []
    
    #@-node:__init__
    #@+node:justSeenPeer
    def justSeenPeer(self, peer):
        """
        Tells the bucket that we've just seen a given node
        """
        nodes = self.nodes
    
        if not isinstance(peer, KPeer):
            raise KBadNode
    
        try:
            idx = nodes.index(peer)
        except:
            idx = -1    
        if idx >= 0:
            del nodes[idx]
            nodes.append(peer)
        else:
            nodes.append(peer)
            
        # might at some time need to implement death-row logic 
        # when we set a bucket size limit - refer __init__
    #@-node:justSeenPeer
    #@+node:__iter__
    def __iter__(self):
        return iter(self.nodes)
    #@-node:__iter__
    #@-others
#@-node:class KBucket
#@+node:class KPeer
class KPeer(KBase):
    """
    Encapsulates a peer node of a L{KNode},
    storing its ID and contact info
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, node, dest):
        """
        Create a ref to a kademlia peer node
        
        Arguments:
            - node - reference to node which has the relationship
              to this peer
            - dest - the peer's I2P destination, as base64
        """
        if not isinstance(node, KNode):
            raise KBadNode(node)
        if not isinstance(dest, str):
            raise KBadDest(dest)
    
        self.node = node
        self.dest = dest
        self.id = KHash(dest)
    
        self.justSeen()
    
    #@-node:__init__
    #@+node:send_ping
    def send_ping(self, **kw):
        """
        Sends a ping to remote peer
        """
        self.send_raw(type="ping", **kw)
    #@-node:send_ping
    #@+node:send_store
    def send_store(self, **kw):
        """
        sends a store command to peer
        """
        self.log(4, "\npeer %s\ndest %s...\nsending store cmd: %s" % (self, self.dest[:12], repr(kw)))
    
        self.send_raw(type="store", **kw)
    
    #@-node:send_store
    #@+node:send_findNode
    def send_findNode(self, hash, **kw):
        """
        sends a findNode command to peer
        """
        if not isinstance(hash, KHash):
            raise KBadHash
    
        self.log(5, "\nquerying peer %s\ntarget hash %s" % (self, hash))
    
        self.send_raw(type="findNode", hash=hash.value, **kw)
    
    #@-node:send_findNode
    #@+node:send_findData
    def send_findData(self, hash, **kw):
        """
        sends a findData command to peer
        """
        if not isinstance(hash, KHash):
            raise KBadHash
    
        self.log(5, "\nquerying peer %s\ntarget hash %s" % (self, hash))
    
        self.send_raw(type="findData", hash=hash.value, **kw)
    
    #@-node:send_findData
    #@+node:send_reply
    def send_reply(self, **kw):
        """
        Sends an RPC reply back to upstream peer
        """
        self.log(5, "\nnode %s\nreplying to peer %s:\n%s" % (
            self.node, self, kw))
        self.send_raw(type="reply", **kw)
    
    #@-node:send_reply
    #@+node:send_raw
    def send_raw(self, **kw):
        """
        Sends a raw datagram to peer
        
        No arguments - just keywords, all of which must be strings or
        other objects which can be bencoded
        """
        self.node._sendRaw(self, **kw)
    #@-node:send_raw
    #@+node:justSeen
    def justSeen(self):
        self.timeLastSeen = time.time()
    
    #@-node:justSeen
    #@+node:lowlevel
    #@+others
    #@+node:__str__
    def __str__(self):
    
        return "<KPeer:%s=>0x%s... dest %s...>" % (
            self.node.name, ("%x" % self.id.value)[:8], self.dest[:8])
    
    #@-node:__str__
    #@+node:__repr__
    def __repr__(self):
        
        return str(self)
    
    #@-node:__repr__
    #@+node:__eq__
    def __eq__(self, other):
    
        #self.log(2, "KPeer: comparing %s to %s (%s to %s)" % (self, other, self.__class__, other.__class__))
        res = self.id == getattr(other, 'id', None)
        #self.log(2, "KPeer: res=%s" % res)
        return res
    
    #@-node:__eq__
    #@+node:__ne__
    def __ne__(self, other):
        return not (self == other)
    #@-node:__ne__
    #@-others
    #@-node:lowlevel
    #@-others
#@-node:class KPeer
#@-node:Basic Classes
#@+node:RPC Classes
#@+node:class KRpc
class KRpc(KBase):
    """
    Base class for RPCs between nodes.
    Refer subclasses
    """
    #@    @+others
    #@+node:attribs
    type = 'unknown' # override in subclass
    
    #@-node:attribs
    #@+node:__init__
    def __init__(self, localNode, client=None, **kw):
        """
        Holds all the information for an RPC
    
        Arguments:
            - localNode - the node from which this RPC is being driven
            - client - a representation of who is initiating this rpc, one of:
                - None - an API caller, which is to be blocked until the RPC completes
                  or times out
                - (upstreamPeer, upstreamMsgId) - an upstream peer
                - callable object - something which requires a callback upon completion
                  in which case the callable will be invoked with the RPC results as the
                  first argument
    
        Keywords:
            - cbArgs - optional - if given, and if client is a callback, the callback
              will be invoked with the results as first argument, and this object as
              second argument
        """
        self.localNode = localNode
    
        if client == None:
            # an api client
            self.isLocal = True
            self.queue = Queue.Queue()
            self.callback = None
        elif callable(client):
            self.isLocal = False
            self.callback = client
        elif isinstance(client, tuple):
            # we're doing the RPC on behalf of an upstream peer
            upstreamPeer, upstreamMsgId = client
            upstreamPeer = localNode._normalisePeer(upstreamPeer)
            self.isLocal = False
            self.upstreamPeer = upstreamPeer
            self.upstreamMsgId = upstreamMsgId
            self.callback = None
    
        # save keywords
        self.__dict__.update(kw)
    
        # set time for receiving a tick.
        # if this is set to an int absolute time value, the on_tick method
        # will be called as soon as possible after that time
        self.nextTickTime = None
    
        # and register with node as a pending command
        self.localNode.rpcPending.append(self)
    
        # now start up the request
        self.start()
    
    #@-node:__init__
    #@+node:__del__
    def __del__(self):
    
        #self.log(4, "\nRPC %s getting the chop" % (str(self)))
        pass
    
    #@-node:__del__
    #@+node:__str__
    def __str__(self):
    
        return "<%s on node %s>" % (self.__class__.__name__, self.localNode.name)
    
    #@-node:__str__
    #@+node:__repr__
    def __repr__(self):
        return str(self)
    #@-node:__repr__
    #@+node:bindPeerReply
    def bindPeerReply(self, peer, msgId):
        """
        Sets up the node to give us a callback when a reply
        comes in from downstream peer 'peer' with msg id 'msgId'
        """
        self.localNode.rpcBindings[(peer.dest, msgId)] = (self, peer)
    
    #@-node:bindPeerReply
    #@+node:unbindPeerReply
    def unbindPeerReply(self, peer, msgId):
        """
        Disables the callback from node for replies
        from peer 'peer' with msgId 'msgId'
        """
        bindings = self.localNode.rpcBindings
        peerdest = peer.dest
        if bindings.has_key((peerdest, msgId)):
            del bindings[(peerdest, msgId)]
    
    #@-node:unbindPeerReply
    #@+node:unbindAll
    def unbindAll(self):
        """
        Remove all reply bindings
        """
        bindings = self.localNode.rpcBindings
        self.log(5, "node bindings before: %s" % bindings)
        for k,v in bindings.items():
            if v[0] == self:
                del bindings[k]
        self.log(5, "node bindings after: %s" % bindings)
    
    #@-node:unbindAll
    #@+node:start
    def start(self):
        """
        Start the RPC running.
        Override this in subclasses
        """
        raise KNotImplemented
    
    #@-node:start
    #@+node:execute
    def execute(self):
        """
        Only for synchronous (application-level) execution.
        Wait for the RPC to complete (or time out) and return
        whatever it came up with
        """
        if core.fg:
            print "servicing background thread"
            while self.queue.empty():
                core.cycle()
    
        return self.queue.get()
    
    #@-node:execute
    #@+node:terminate
    def terminate(self):
        """
        Clean up after ourselves.
        Mainly involves removing ourself from local node
        """
        self.unbindAll()
        try:
            self.localNode.rpcPending.remove(self)
        except:
            #traceback.print_exc()
            pass
    
    #@-node:terminate
    #@+node:returnValue
    def returnValue(self, result=None, **kw):
        """
        Passes a return value back to the original caller, be it
        the local application, or an upstream peer
        
        Arguments:
            - just one - a result object to pass back, if this RPC
              was instigated by a local application call.
              Note that if this RPC was instigated by an upstream
              peer, this will be ignored.
        
        Keywords:
            - the items to return, in the case that this RPC was
              instigated by an upstream peer. Ignored if this
              RPC was instigated by a local application call.
              Note - the RPC invocation/reply dict keys are
              listed at the top of this source file.
        """
        self.terminate()
        if self.callback:
            if hasattr(self, 'cbArgs'):
                self.callback(result, self.cbArgs)
            else:
                self.callback(result)
        elif self.isLocal:
            self.queue.put(result)
        else:
            self.upstreamPeer.send_reply(msgId=self.upstreamMsgId,
                                         **kw)
    #@-node:returnValue
    #@+node:on_reply
    def on_reply(self, peer, msgId, **details):
        """
        Callback which fires when a downstream peer replies
        
        Override this in subclasses
        """
        raise KNotImplemented
    
    #@-node:on_reply
    #@+node:on_tick
    def on_tick(self):
        """
        Callback which fires if the whole RPC times out, in which
        case the RPC should return whatever it can
        
        Override in subclasses
        """
        self.localNode.rpcPending.remove(self)
    
    #@-node:on_tick
    #@-others
#@-node:class KRpc
#@+node:PING
#@+node:class KRpcPing
class KRpcPing(KRpc):
    """
    Implements the PING rpc as per Kademlia spec
    """
    #@    @+others
    #@+node:attribs
    type = 'ping'
    
    #@-node:attribs
    #@+node:__init__
    def __init__(self, localNode, client=None, **kw):
        """
        Creates and performs a PING RPC
    
        Arguments:
            - localNode - the node performing this RPC
            - upstreamPeer - if given, the peer wanting a reply
            - upstreamMsgId - if upstreamPeer is given, this is the msgId
              of the RPC message from the upstream peer
    
        Keywords:
            - peer - the peer to ping - default is local node
        """
        peer = kw.get('peer', None)
        if peer != None:
            peer = localNode._normalisePeer(peer)
        self.peerToPing = peer
    
        if kw.has_key('cbArgs'):
            KRpc.__init__(self, localNode, client, cbArgs=kw['cbArgs'])
        else:
            KRpc.__init__(self, localNode, client)
    
    #@-node:__init__
    #@+node:start
    def start(self):
        """
        Sends out the ping
        """
        peer = self.peerToPing
    
        # are we ourselves being pinged?
        if peer == None:
            # yes, just reply
            self.returnValue(True)
            return
    
        # no - we need to ping a peer
        thisNode = self.localNode
    
        msgId = thisNode.msgId = thisNode._msgIdAlloc()
    
        # bind for peer response
        self.bindPeerReply(peer, msgId)
    
        # and send it off
        self.log(3, "node %s sending ping" % self.localNode.name)
        peer.send_ping(msgId=msgId)
    
        # and set a reply timeout
        self.nextTickTime = time.time() + timeout['ping']
    
    #@-node:start
    #@+node:on_reply
    def on_reply(self, peer, msgId, **details):
        """
        Callback for PING reply
        """
        self.log(3, "got ping reply from %s" % peer)
        self.returnValue(True)
    
    #@-node:on_reply
    #@+node:on_tick
    def on_tick(self):
        """
        'tick' handler.
        
        For PING RPC, the only time we should get a tick is when the ping
        has timed out
        """
        self.log(3, "timeout awaiting ping reply from %s" % self.peerToPing)
        self.returnValue(False)
    
    #@-node:on_tick
    #@-others
#@-node:class KRpcPing
#@-node:PING
#@+node:FIND_NODE
#@+node:class KPeerQueryRecord
class KPeerQueryRecord(KBase):
    """
    Keeps state information regarding a peer we're quering
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, peer, table, state=None, **kw):
    
        self.peer = peer
        self.dest = peer.dest
        self.deadline = time.time() + timeout['findNode']
        self.table = table
    
        # state is always one of:
        #  - 'start'        - have not yet sent query to peer
        #  - 'recommended'  - peer was recommended by another peer, no query sent
        #  - 'queried'      - sent query, awaiting reply or timeout
        #  - 'replied'      - this peer has replied to our query
        #  - 'timeout'      - timed out waiting for peer reply
        #  - 'toofar'       - too far away to be of interest
        #  - 'closest'      - this peer is one of the closest so far
    
        if state == None:
            state = 'start'
        if not isinstance(state, str):
            raise Exception("Invalid state %s" % state)
    
        self.state = state
    
        self.__dict__.update(kw)
    
    #@-node:__init__
    #@+node:hasTimedOut
    def hasTimedOut(self, now=None):
        if now == None:
            now = time.time()
        return self.state == 'queried' and now > self.deadline
    
    #@-node:hasTimedOut
    #@+node:__cmp__
    def __cmp__(self, other):
    
        return cmp(self.peer.id.rawdistance(self.table.sorthash),
                   other.peer.id.rawdistance(self.table.sorthash))
    
    #@-node:__cmp__
    #@+node:__lt__ etc
    def __lt__(self, other):
        return (cmp(self, other) < 0)
    
    def __le__(self, other):
        return (cmp(self, other) <= 0)
    
    def __gt__(self, other):
        return (cmp(self, other) > 0)
    
    def __ge__(self, other):
        return (cmp(self, other) <= 0)
    
    #@-node:__lt__ etc
    #@+node:isCloserThanAllOf
    def isCloserThanAllOf(self, tab):
        """
        returns True if this peerRec is closer to the desired hash
        than all of the peerRecs in table 'tab'
        """
        if not isinstance(tab, KPeerQueryTable):
            self.log(2, "invalid qtable %s" % repr(tab))
            raise Exception("invalid qtable %s" % repr(tab))
        
        for rec in tab:
            if self > rec:
                return False
        return True
    
    #@-node:isCloserThanAllOf
    #@+node:isCloserThanOneOf
    def isCloserThanOneOf(self, tab):
        """
        returns True if this peerRec is closer to the desired hash
        than one or more of of the peerRecs in table 'tab'
        """
        if not isinstance(tab, KPeerQueryTable):
            self.log(2, "invalid qtable %s" % repr(tab))
            raise Exception("invalid qtable %s" % repr(tab))
        
        for rec in tab:
            if self < rec:
                return True
        return False
    
    #@-node:isCloserThanOneOf
    #@-others
#@-node:class KPeerQueryRecord
#@+node:class KPeerQueryTable
class KPeerQueryTable(KBase):
    """
    Holds zero or more instances of KPeerQuery and
    presents/sorts table in different forms
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, lst=None, sorthash=None, state=None, **kw):
        self.peers = []
        if lst == None:
            lst = []
        else:
            self.setlist(lst, state, **kw)
        self.sorthash = sorthash
    
    #@-node:__init__
    #@+node:setlist
    def setlist(self, lst, state=None, **kw):
        for item in lst:
            self.append(item, state, **kw)
    
    #@-node:setlist
    #@+node:getExpired
    def getExpired(self):
        """
        return a list of peers which have expired
        """
        return KPeerQueryTable(
                filter(lambda item: item.hasTimedOut(), self.peers),
                self.sorthash
                )
    
    #@-node:getExpired
    #@+node:purgeExpired
    def purgeExpired(self):
        """
        Eliminate peers which have expired
        """
        for peer in self.peers:
            if peer.hasTimedOut():
                self.peers.remove(peer)
    
    #@-node:purgeExpired
    #@+node:sort
    def sort(self):
        """
        Sort the table in order of increasing distance from self.sorthash
        """
        self.peers.sort()
    
    #@-node:sort
    #@+node:select
    def select(self, criterion):
        """
        Returns a table of items for which criterion(item) returns True
        Otherwise, if 'criterion' is a string, returns the items whose
        state == criterion.
        Otherwise, if 'criterion' is a list or tuple, return the items
        whose state is one of the elements in criterion
        """
        if callable(criterion):
            func = criterion
        elif type(criterion) in [type(()), type([])]:
            func = lambda p: p.state in criterion
        else:
            func = lambda p: p.state == criterion
    
        recs = []
        for peerRec in self.peers:
            if func(peerRec):
                recs.append(peerRec)
        return self.newtable(recs)
    
    #@-node:select
    #@+node:count
    def count(self, *args):
        """
        returns the number of records whose state is one of args
        """
        count = 0
        for rec in self.peers:
            if rec.state in args:
                count += 1
        return count
    
    #@-node:count
    #@+node:changeState
    def changeState(self, oldstate, newstate):
        """
        for all recs of state 'oldstate', change their
        state to 'newstate'
        """
        for p in self.peers:
            if p.state == oldstate:
                p.state = newstate
    #@-node:changeState
    #@+node:filter
    def filter(self, func):
        """
        Eliminate, in place, all items where func(item) returns False
        """
        for peerRec in self.peers:
            if not func(peerRec):
                self.peers.remove(peerRec)
    
    #@-node:filter
    #@+node:purge
    def purge(self, func):
        """
        Eliminate, in place, all items where func(item) returns True
        """
        if 0 and desperatelyDebugging:
            set_trace()
        for peerRec in self.peers:
            if func(peerRec):
                self.peers.remove(peerRec)
    
    #@-node:purge
    #@+node:chooseN
    def chooseN(self, n):
        """
        Randomly select n peer query records
        """
        candidates = self.peers[:]
    
        self.log(3, "candidates = %s" % repr(candidates))
    
        chosen = []
        i = 0
    
        if len(candidates) <= n:
            chosen = candidates
        else:
            while i < n:
                try:
                    peer = random.choice(candidates)
                except:
                    self.log(2, "failed to choose one of %s" % repr(candidates))
                    raise
                chosen.append(peer)
                candidates.remove(peer)
                i += 1
    
        return self.newtable(chosen)
    
    #@-node:chooseN
    #@+node:__str__
    def __str__(self):
        return "<KPeerQueryTable: %d peers>" % len(self) #.peers)
    
    def __repr__(self):
        return str(self)
    
    #@-node:__str__
    #@+node:newtable
    def newtable(self, items, state=None, **kw):
        """
        Returns a new KPeerQueryTable object, based on this
        one, but containing 'items'
        """
        tab = KPeerQueryTable(items, sorthash=self.sorthash, state=state, **kw)
        return tab
    
    #@-node:newtable
    #@+node:dump
    def dump(self):
    
        c = self.count
        self.log(2,
                "PeerQueryTable stats:\n"
                "start:       %s\n"
                "recommended: %s\n"
                "queried:     %s\n"
                "replied:     %s\n"
                "timeout:     %s\n"
                "closest:     %s\n"
                "toofar:      %s\n"
                "TOTAL:       %s\n" % (
                    c('start'),
                    c('recommended'),
                    c('queried'),
                    c('replied'),
                    c('timeout'),
                    c('closest'),
                    c('toofar'),
                    len(self.peers)))
                    
        #states = [p.state for p in self.peers]
        #self.log(3, "PeerQueryTable states:\n%s" % states)
    
    #@-node:dump
    #@+node:list-like methods
    #@+node:extend
    def extend(self, items, state, **kw):
        for item in items:
            self.append(item, state, **kw)
    
    #@-node:extend
    #@+node:append
    def append(self, item, state=None, **kw):
    
        if isinstance(item, KPeerQueryRecord):
            self.log(5, "adding a KPeerQueryRecord, state=%s" % state)
            if state != None:
                item.state = state
            item.__dict__.update(kw)
            peerRec = item
    
        elif isinstance(item, KPeer):
            self.log(5, "adding a KPeer")
            peerRec = KPeerQueryRecord(item, self, state, **kw)
    
        else:
            self.log(2, "bad peer %s" % repr(item))
            raise KBadPeer
    
        if peerRec not in self:
            self.log(5, "peerRec=%s list=%s" % (peerRec, self.peers))
            self.peers.append(peerRec)
        else:
            self.log(5, "trying to append duplicate peer???")
    
    #@-node:append
    #@+node:remove
    def remove(self, item):
        self.peers.remove(item)
    
    #@-node:remove
    #@+node:__getitem__
    def __getitem__(self, idx):
        """
        Allow the table to be indexed by any of:
            - KPeerQueryRecord
            - integer index
            - long string - treated as dest
            - short string - treated as peer id hash string
            - KHash - finds peer with that id
            - KPeer - returns peer with that peer
        """
        if type(idx) == type(0):
            return self.peers[idx]
        elif isinstance(idx, KPeer):
            for peer in self.peers:
                if peer.peer == idx:
                    return peer
            raise IndexError("Query table has no peer %s" % idx)
        elif isinstance(idx, str):
            if len(str) > 512:
                for peer in self.peers:
                    if peer.peer.dest == idx:
                        return peer
                raise IndexError("No peer with dest %s" % idx)
            else:
                for peer in self.peers:
                    if peer.peer.id.value == idx:
                        return peer
                raise IndexError("No peer with dest hash %s" % idx)
        elif isinstance(idx, KHash):
            for peer in self.peers:
                if peer.peer.id == idx:
                    return peer
                raise IndexError("No peer with id %s" % idx)
        else:
            raise IndexError("Invalid selector %s" % repr(idx))
    
    #@-node:__getitem__
    #@+node:__len__
    def __len__(self):
        return len(self.peers)
    
    #@-node:__len__
    #@+node:__getslice__
    def __getslice__(self, fromidx, toidx):
        return KPeerQueryTable(self.peers[fromidx:toidx], self.sorthash)
    
    #@-node:__getslice__
    #@+node:__iter__
    def __iter__(self):
        return iter(self.peers)
    
    #@-node:__iter__
    #@+node:__add__
    def __add__(self, other):
        self.extend(other)
    
    #@-node:__add__
    #@+node:__contains__
    def __contains__(self, other):
        self.log(5, "testing if %s is in %s" % (other, self.peers))
        for peerRec in self.peers:
            if peerRec.peer.dest == other.peer.dest:
                return True
        return False
    
    #@-node:__contains__
    #@-node:list-like methods
    #@-others
#@-node:class KPeerQueryTable
#@+node:class KRpcFindNode
class KRpcFindNode(KRpc):
    """
    Implements the FIND_NODE rpc as per Kademlia spec
    """
    #@    @+others
    #@+node:spec info comments
    #@+at
    # Verbatim extract from original Kademlia paper follows:
    # 
    # The lookup initiator starts by picking x nodes from its closest
    # non-empty k-bucket (or, if that bucket has fewer than x
    # entries, it just takes the closest x nodes it knows of).
    # 
    # The initiator then sends parallel, asynchronous
    # FIND NODE RPCs to the x nodes it has chosen.
    # x is a system-wide concurrency parameter, such as 3.
    # 
    # In the recursive step, the initiator resends the
    # FIND NODE to nodes it has learned about from previous RPCs.
    # 
    # [Paraphrased - in the recursive step, the initiator sends a FIND_NODE to
    # each of the nodes that were returned as results of these previous
    # FIND_NODE RPCs.]
    # 
    # (This recursion can begin before all of the previous RPCs have
    # returned).
    # 
    # Of the k nodes the initiator has heard of closest to
    # the target, it picks x that it has not yet queried and resends
    # the FIND_NODE RPC to them.
    # 
    # Nodes that fail to respond quickly are removed from consideration
    # until and unless they do respond.
    # 
    # If a round of FIND_NODEs fails to return a node any closer
    # than the closest already seen, the initiator resends
    # the FIND NODE to all of the k closest nodes it has
    # not already queried.
    # 
    # The lookup terminates when the initiator has queried and gotten
    # responses from the k closest nodes it has seen.
    #@-at
    #@-node:spec info comments
    #@+node:attribs
    type = 'findNode'
    #@-node:attribs
    #@+node:__init__
    def __init__(self, localNode, client=None, **kw):
        """
        Creates and launches the findNode rpc
        
        Arguments:
            - localNode - the node performing this RPC
            - client - see KRpc.__init__
    
        Keywords:
            - hash - a string, long int or KHash object representing
              what we're looking for. treatment depends on type:
                  - KHash object - used as is
                  - string - gets wrapped into a KHash object
                  - long int - wrapped into a KHash object
              refer KHash.__init__
            - raw - whether 'hash' is already a hash, default True
            - local - True/False - whether to only search local store,
              or pass on the query to the network, default True
        """
        kw = dict(kw)
        if kw.get('raw', False):
            h = kw['hash']
            del kw['hash']
            kw['raw'] = h
            self.hashWanted = KHash(**kw)
        else:
            self.hashWanted = KHash(kw['hash'], **kw)
        self.isLocalOnly = kw.get('local', True)
    
        self.numQueriesPending = 0
    
        self.numRounds = 0   # count number of rounds
        self.numReplies = 0  # number of query replies received
        self.numQueriesSent = 0
        self.numPeersRecommended = 0
    
        # whichever mode we're called from, we gotta find the k closest peers
        self.localNode = localNode
        self.peerTab = self.findClosestPeersInitial()
    
        self.log(4, "KRpcFindNode: isLocalOnly=%s" % self.isLocalOnly)
    
        if kw.has_key('cbArgs'):
            KRpc.__init__(self, localNode, client, cbArgs=kw['cbArgs'])
        else:
            KRpc.__init__(self, localNode, client)
    
    #@-node:__init__
    #@+node:start
    def start(self):
        """
        Kicks off this RPC
        """
        # if we're being called by an upstream initiator, just return the peer list
        if self.isLocalOnly:
            peerDests = [peer.dest for peer in self.peerTab]
            self.log(5, "findNode: local only: returning to upstream with %s" % repr(peerDests))
            self.returnValue(peerDests)
            return
    
        # just return nothing if we don't have any peers
        if len(self.peerTab) == 0:
            self.returnValue([])
            return
    
        # send off first round of queries
        self.sendSomeQueries()
    
        return
    
    #@-node:start
    #@+node:sendSomeQueries
    def sendSomeQueries(self, **kw):
        """
        First step of findNode
        
        Select alpha nodes that we haven't yet queried, and send them queries
        """
        # bail if too busy
        if self.numQueriesPending >= maxConcurrentQueries:
            return
    
        # shorthand
        localNode = self.localNode
        hashWanted = self.hashWanted
    
        # randomly choose some peers
        #somePeerRecs = self.peerTab.chooseN(numSearchPeers)
        somePeerRecs = self.peerTab.select('start')
        
        # start our ticker
        self.nextTickTime = time.time() + timeout['findNode']
    
        numQueriesSent = 0
    
        # and send them findNode queries
        if len(somePeerRecs) > 0:
            for peerRec in somePeerRecs:
                self.log(3, "querying %s" % peerRec)
                if self.numQueriesPending < maxConcurrentQueries:
                    self.sendOneQuery(peerRec)
                    numQueriesSent += 1
                else:
                    break
            self.log(3, "%s queries sent, awaiting reply" % numQueriesSent)
        else:
            self.log(3, "no peer recs???")
            for peerRec in self.peerTab:
                self.log(4, "%s state=%s, dest=%s..." % (peerRec, peerRec.state, peerRec.dest[:12]))
    
    #@-node:sendSomeQueries
    #@+node:sendOneQuery
    def sendOneQuery(self, peerRec):
        """
        Sends off a query to a single peer
        """
        if peerRec.state != 'start':
            self.log(2, "duh!! peer state %s:\n%s" % (peerRec.state, peerRec))
            return 
    
        msgId = self.localNode._msgIdAlloc()
        self.bindPeerReply(peerRec.peer, msgId)
        peerRec.msgId = msgId
    
        if self.type == 'findData':
            peerRec.peer.send_findData(hash=self.hashWanted, msgId=msgId)
        else:
            peerRec.peer.send_findNode(hash=self.hashWanted, msgId=msgId)
    
        peerRec.state = 'queried'
    
        self.numQueriesPending += 1
    
        self.numQueriesSent += 1
    
    #@-node:sendOneQuery
    #@+node:findClosestPeersInitial
    def findClosestPeersInitial(self):
        """
        Searches our k-buckets, and returns a table of k of
        peers closest to wanted hash into self.closestPeersInitial
        """
        hashobj = self.hashWanted
    
        lst = []
        buckets = self.localNode.buckets
        for bucket in buckets:
            for peer in bucket:
                lst.append(peer)
    
        table = KPeerQueryTable(lst, self.hashWanted, 'start')
        table.sort()
    
        return table[:maxBucketSize]
    
    #@-node:findClosestPeersInitial
    #@+node:addPeerIfCloser
    def addPeerIfCloser(self, peer):
        """
        Maintains the private .peersToQuery array.
        If the array is not yet maxed (ie, length < maxBucketSize),
        the peer is simply added.
        However, if the array is maxed, it finds the least-close peer,
        and replaces it with the given peer if closer.
        """
    #@-node:addPeerIfCloser
    #@+node:isCloserThanQueried
    def isCloserThanQueried(self, peer):
        """
        Test function which returns True if argument 'peer'
        is closer than all the peers in self.peersAlreadyQueried,
        or False if not
        """
        for p in self.peersAlreadyQueried:
            if p.id.rawdistance(self.hashWanted) < peer.id.rawdistance(self.hashWanted):
                return False
        return True
    
    #@-node:isCloserThanQueried
    #@+node:on_reply
    def on_reply(self, peer, msgId, **details):
        """
        Callback for FIND_NODE reply
        """
        # shorthand
        peerTab = self.peerTab
    
        self.numReplies += 1
    
        # ------------------------------------------------------------
        # determine who replied, and get the raw dests sent back
        try:
            peerRec = peerTab[peer]
        except:
            traceback.print_exc()
            self.log(3, "discarding findNode reply from unknown peer %s %s, discarding" % (
                peer, details))
            return
    
        # one less query to wait for
        self.numQueriesPending -= 1
    
        # ----------------------------------------------------------
        # peerRec is the peer that replied
        # peers is a list of raw dests
    
        # save ref to this peer, it's seemingly good
        self.localNode.addref(peerRec.peer)
    
        # mark it as having replied
        if peerRec.state != 'queried':
            self.log(2, "too weird - got a reply from a peer we didn't query")
        peerRec.state = 'replied'
    
        # wrap the returned peers as KPeer objects
        peersReturned = details.get('nodes', [])
        peersReturned = [self.localNode._normalisePeer(p) for p in peersReturned]
    
        self.numPeersRecommended += len(peersReturned)
    
        # and add them to table in state 'recommended'
        for p in peersReturned:
            peerTab.append(p, 'recommended')
    
        # try to fire off more queries
        self.sendSomeQueries()
    
        # and check for and action possible end of query round
        self.checkEndOfRound()
    
    #@-node:on_reply
    #@+node:on_tick
    def on_tick(self):
        """
        Callback for FIND_NODE reply timeout
        """
        # check for timeouts, and update offending peers
        now = time.time()
        for peerRec in self.peerTab:
            if peerRec.hasTimedOut(now):
                peerRec.state = 'timeout'
    
                # makes room for more queries
                self.sendSomeQueries()
    
        # possible end of round
        self.checkEndOfRound()
    
        # schedule next tick
        self.nextTickTime = time.time() + 5
    
    #@-node:on_tick
    #@+node:checkEndOfRound
    def checkEndOfRound(self):
        """
        Checks if we've hit the end of a query round.
        If so, and if either:
            - we've got some closer peers, OR
            - we've heard from less than maxBucketSize peers,
        fire off more queries
        
        Otherwise, return the best available
        """
        peerTab = self.peerTab
    
        if core.fg:
            set_trace()
    
        # has this query round ended?
        if peerTab.count('start', 'queried') > 0:
            # not yet
            return
    
        self.log(2, "********** query round ended")
    
        # ------------------------------------
        # end of round processing
    
        self.numRounds += 1
    
        # did we get any closer to required hash?
        if self.type == 'findData' \
                or self.gotAnyCloser() \
                or peerTab.count('closest') < maxBucketSize:
    
            # yes - save these query results
            self.log(4, "starting another round")
            peerTab.changeState('replied', 'closest')
            peerTab.changeState('recommended', 'start')
    
            # cull the shortlist
            self.log(2, "culling to k peers")
            if peerTab.count('closest') > maxBucketSize:
                peerTab.sort()
                excess = peerTab.select('closest')[maxBucketSize:]
                excess.changeState('closest', 'toofar')
                pass
    
            # and start up another round
            self.sendSomeQueries()
    
        # did anything launch?
        if peerTab.count('start', 'queried') == 0:
            # no - we're screwed
            self.returnTheBestWeGot()
            
        # done for now
        return
    
    #@-node:checkEndOfRound
    #@+node:gotAnyCloser
    def gotAnyCloser(self):
        """
        Tests if any peer records in state 'recommended' or 'replied'
        are nearer than the records in state 'closest'
        """
        peerTab = self.peerTab
    
        # get current closest peers
        closest = peerTab.select('closest')
    
        # if none yet, then this was just end of first round
        if len(closest) == 0:
            return True
    
        # get the peers we're considering
        #candidates = peerTab.select(('recommended', 'replied'))
        candidates = peerTab.select('recommended')
    
        # now test them
        gotOneCloser = False
        for c in candidates:
            #if c.isCloserThanOneOf(closest):
            if c.isCloserThanAllOf(closest):
                return True
    
        # none were closer
        return False
    
    #@-node:gotAnyCloser
    #@+node:returnTheBestWeGot
    def returnTheBestWeGot(self):
        """
        Returns the k closest nodes to the wanted hash that we have
        actually heard from
        """
        # pick the peers which have replied to us
        closest = self.peerTab.select('closest')
        
        self.peerTab.dump()
    
        # add ourself to the list - we could easily be one of the best
        localNode = self.localNode
        selfDest = localNode._normalisePeer(localNode.dest) 
        closest.append(selfDest, state='closest')
        
        # sort in order of least distance first
        closest.sort()
    
        # pick the best k of these
        #peersHeardFrom = peersHeardFrom[:maxBucketSize]
        #peersHeardFrom = peersHeardFrom[:numSearchPeers]
    
        # extract their dest strings
        peers = [p.peer.dest for p in closest]
    
        # pass these back
        self.returnValue(peers)
    
        # and we're done
        return
    
    #@-node:returnTheBestWeGot
    #@+node:returnValue
    def returnValue(self, items):
        """
        override with a nicer call sig
        """
        # a hack for testing - save this RPC object into the node
        # so we can introspect it
        self.localNode.lastrpc = self
    
        items = items[:maxBucketSize]
    
        self.reportStats()
    
        KRpc.returnValue(self, items, nodes=items)
    
    #@-node:returnValue
    #@+node:reportStats
    def reportStats(self):
        """
        Logs a stat dump of query outcome
        """
        if self.isLocalOnly:
            return
        self.log(2,
            "query terminated after %s rounds, %s queries, %s replies, %s recommendations" % (
               (self.numRounds+1),
               self.numQueriesSent,
               (self.numReplies+1),
               self.numPeersRecommended
               )
            )
    #@-node:reportStats
    #@-others
#@-node:class KRpcFindNode
#@-node:FIND_NODE
#@+node:FIND_DATA
#@+node:class KRpcFindData
class KRpcFindData(KRpcFindNode):
    """
    variant of KRpcFindNode which returns key value if found
    """
    #@    @+others
    #@+node:attribs
    type = 'findData'
    #@-node:attribs
    #@+node:start
    def start(self):
        """
        Kicks off the RPC.
        If requested key is stored locally, simply returns it.
        Otherwise, falls back on parent method
        """
        # if we posses the data, just return the data
        value = self.localNode.storage.getKey(self.hashWanted.asHex(), keyIsHashed=True)
        if value != None:
            self.log(4, "Found required value in local storage")
            self.log(4, "VALUE='%s'" % value)
            self.returnValue(value)
            return
    
        # no such luck - pass on to parent
        KRpcFindNode.start(self)
    
    #@-node:start
    #@+node:on_reply
    def on_reply(self, peer, msgId, **details):
        """
        Callback for FIND_NODE reply
        """
        res = details.get('nodes', None)
        if isinstance(res, str):
            self.returnValue(res)
        else:
            KRpcFindNode.on_reply(self, peer, msgId, **details)
    
    #@-node:on_reply
    #@+node:returnValue
    def returnValue(self, items):
        """
        override with a nicer call sig
        """
        # a hack for testing - save this RPC object into the node
        # so we can introspect it
        self.localNode.lastrpc = self
    
        self.reportStats()
    
        KRpc.returnValue(self, items, nodes=items)
    
    #@-node:returnValue
    #@-others

#@-node:class KRpcFindData
#@-node:FIND_DATA
#@+node:STORE
#@+node:class KRpcStore
class KRpcStore(KRpc):
    """
    Implements key storage
    """
    #@    @+others
    #@+node:attribs
    type = 'store'
    #@-node:attribs
    #@+node:__init__
    def __init__(self, localNode, client=None, **kw):
        """
        Creates and launches a STORE rpc
        
        Arguments:
            - localNode - the node performing this RPC
            - client - see KRpc.__init__
    
        Keywords:
            - key - the key under which we wish to save the data
            - value - the value we wish to save
            - local - True/False:
                - if True, only save in local store
                - if False, do a findNode to find the nodes to save the
                  key to, and tell them to save it
              default is True
        """
        self.key = kw['key']
        #self.keyHashed = shahash(self.key)
        self.keyHashed = self.key
        self.value = kw['value']
        self.isLocalOnly = kw.get('local', True)
    
        self.log(4, "isLocalOnly=%s" % self.isLocalOnly)
    
        if kw.has_key('cbArgs'):
            KRpc.__init__(self, localNode, client, cbArgs=kw['cbArgs'])
        else:
            KRpc.__init__(self, localNode, client)
    
    #@-node:__init__
    #@+node:start
    def start(self):
        """
        Kicks off this RPC
        """
        # if local only, or no peers, just save locally
        if self.isLocalOnly or len(self.localNode.peers) == 0:
            result = self.localNode.storage.putKey(self.keyHashed, self.value, keyIsHashed=True)
            if result:
                result = 1
            else:
                result = 0
            self.returnValue(result)
            return
    
        # no - se have to find peers to store the key to, and tell them to
        # store the key
        
        # launch a findNode rpc, continue in our callback
        KRpcFindNode(self.localNode, self.on_doneFindNode,
                     hash=self.keyHashed, raw=True, local=False)
        return
    
    #@-node:start
    #@+node:returnValue
    def returnValue(self, result):
        """
        an override with a nicer call sig
        """
        # a hack for testing - save this RPC object into the node
        # so we can introspect it
        self.localNode.lastrpc = self
    
        try:
            KRpc.returnValue(self, result, status=result)
        except:
            traceback.print_exc()
            self.log(3, "Failed to return %s" % repr(result))
            KRpc.returnValue(self, 0, status=0)
    
    #@-node:returnValue
    #@+node:on_doneFindNode
    def on_doneFindNode(self, lst):
        """
        Receive a callback from findNode
        
        Send STORE command to each node that comes back
        """
        localNode = self.localNode
    
        # normalise results
        normalisePeer = localNode._normalisePeer
        peers = [normalisePeer(p) for p in lst] # wrap in KPeer objects
    
        self.log(2, "STORE RPC findNode - got peers %s" % repr(peers))
    
        i = 0
    
        self.numPeersSucceeded = 0
        self.numPeersFailed = 0
        self.numPeersFinished = 0
    
        # and fire off store messages for each peer
        for peer in peers:
    
            if peer.dest == localNode.dest:
                self.log(3, "storing to ourself")
                localNode.storage.putKey(self.keyHashed, self.value, keyIsHashed=True)
                self.numPeersSucceeded += 1
                self.numPeersFinished += 1
            else:
                msgId = self.localNode._msgIdAlloc()
                self.log(4, "forwarding store cmd to peer:\npeer=%s\nmsgId=%s" % (peer, msgId))
                self.bindPeerReply(peer, msgId)
                peer.send_store(key=self.keyHashed, value=self.value, msgId=msgId)
            i += 1
            if i >= numStorePeers:
                break
    
        self.nextTickTime = time.time() + timeout['store']
    
        self.log(2, "Sent store cmd to %s peers, awaiting responses" % i)
    
        self.numPeersToStore = i
    
    
    #@-node:on_doneFindNode
    #@+node:on_reply
    def on_reply(self, peer, msgId, **details):
        """
        callback which fires when we get a reply from a STORE we sent to a
        peer
        """
        self.numPeersSucceeded += 1
        self.numPeersFinished += 1
        
        if self.numPeersFinished == self.numPeersToStore:
            # rpc is finished
            self.returnValue(True)
    
    #@-node:on_reply
    #@+node:on_tick
    def on_tick(self):
    
        self.log(3, "Timeout awaiting store reply from %d out of %d peers" % (
            self.numPeersToStore - self.numPeersSucceeded, self.numPeersToStore))
    
        if self.numPeersSucceeded == 0:
            self.log(3, "Store timeout - no peers replied, storing locally")
            self.localNode.storage.putKey(self.keyHashed, self.value, keyIsHashed=True)
    
        self.returnValue(True)
    
    #@-node:on_tick
    #@-others
#@-node:class KRpcStore
#@-node:STORE
#@+node:PINGALL
#@+node:class KRpcPingAll
class KRpcPingAll(KRpc):
    """
    Pings all peers
    """
    #@    @+others
    #@+node:attribs
    type = 'pingall'
    #@-node:attribs
    #@+node:__init__
    def __init__(self, localNode, client=None, **kw):
        """
        Creates and launches a PINGALL rpc
    
        Arguments:
            - localNode - the node performing this RPC
            - client - see KRpc.__init__
    
        Keywords: none
        """
        if kw.has_key('cbArgs'):
            KRpc.__init__(self, localNode, client, cbArgs=kw['cbArgs'])
        else:
            KRpc.__init__(self, localNode, client)
    
    #@-node:__init__
    #@+node:start
    def start(self):
        """
        Kicks off this RPC
        """
        # launch a findNode rpc against each of our peers
        peers = self.localNode.peers
        self.numSent = self.numPending = len(peers)
        self.numReplied = self.numFailed = 0
        for peer in peers:
            KRpcPing(self.localNode, self.on_reply, peer=peer)
        return
    
    #@-node:start
    #@+node:on_reply
    def on_reply(self, result):
        """
        callback which fires when we get a reply from a STORE we sent to a
        peer
        """
        log(3, "got %s" % repr(result))
    
        if result:
            self.numReplied += 1
        else:
            self.numFailed += 1
        self.numPending -= 1
    
        if self.numPending <= 0:
            res = "pinged:%s replied:%s timeout:%s" % (
                    self.numSent, self.numReplied, self.numFailed)
            self.log(3, res)
            self.returnValue(res)
    
    #@-node:on_reply
    #@+node:on_tick
    def on_tick(self):
    
        self.log(3, "this shouldn't have happened")
        self.returnValue(False)
    
    #@-node:on_tick
    #@+node:returnValue
    def returnValue(self, result):
        """
        an override with a nicer call sig
        """
        # a hack for testing - save this RPC object into the node
        # so we can introspect it
        self.localNode.lastrpc = self
    
        try:
            KRpc.returnValue(self, result, status=result)
        except:
            traceback.print_exc()
            self.log(3, "Failed to return %s" % repr(result))
            KRpc.returnValue(self, 0, status=0)
    
    #@-node:returnValue
    #@-others
#@-node:class KRpcPingAll
#@-node:PINGALL
#@-node:RPC Classes
#@+node:Node Socket Server
#@+node:class KNodeServer
class KNodeServer(KBase, SocketServer.ThreadingMixIn, SocketServer.TCPServer):
    """
    Listens for incoming socket connections
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, node, addr=None):
    
        if addr == None:
            addr = clientAddr
    
        self.isRunning = True
    
        self.node = node
    
        listenHost, listenPort = addr.split(":")
        listenPort = int(listenPort)
        self.listenPort = listenPort
        SocketServer.TCPServer.__init__(self, (listenHost, listenPort), KNodeReqHandler)
    
    #@-node:__init__
    #@+node:serve_forever
    def serve_forever(self):
    
        print "awaiting client connections on port %s" % self.listenPort
        while self.isRunning:
            self.handle_request()
    
    #@-node:serve_forever
    #@-others
#@-node:class KNodeServer
#@+node:class KNodeReqHandler
class KNodeReqHandler(KBase, SocketServer.StreamRequestHandler):
    """
    Manages a single client connection
    """
    #@    @+others
    #@+node:handle
    def handle(self):
        """
        Conducts all conversation for a single req
        """
        req = self.request
        client = self.client_address
        server = self.server
        node = self.server.node
    
        read = self.rfile.read
        readline = self.rfile.readline
        write = self.wfile.write
        flush = self.wfile.flush
        
        finish = self.finish
        
        # start with a greeting
        write("Stasher version %s ready\n" % version)
        
        # get the command
        line = readline().strip()
    
        try:
            cmd, args = re.split("\\s+", line, 1)
        except:
            cmd = line
            args = ''
    
        self.log(3, "cmd=%s args=%s" % (repr(cmd), repr(args)))
        
        if cmd == "get":
            value = node.get(args)
            if value == None:
                write("notfound\n")
            else:
                write("ok\n%s\n%s" % (len(value), value))
            flush()
            time.sleep(2)
            finish()
            return
        
        elif cmd == "put":
            try:
                size = int(readline())
                value = read(size)
                res = node.put(args, value)
                if res:
                    write("ok\n")
                else:
                    write("failed\n")
                flush()
            except:
                traceback.print_exc()
                write("exception\n")
            finish()
            return
    
        elif cmd == 'addref':
            try:
                res = node.addref(args, True)
                if res:
                    write("ok\n")
                else:
                    write("failed\n")
                flush()
            except:
                traceback.print_exc()
                write("exception\n")
            finish()
            return
    
        elif cmd == 'getref':
            res = node.dest
            write("ok\n")
            write("%s\n" % res)
            flush()
            time.sleep(1)
            finish()
            return
    
        elif cmd == 'pingall':
            res = node._pingall()
            write(res+"\n")
            finish()
            return
    
        elif cmd == "die":
            server.isRunning = False
            write("server terminated\n")
            finish()
    
        else:
            write("unrecognisedcommand\n")
            finish()
            return
    
    #@-node:handle
    #@+node:finish
    def finish(self):
    
        SocketServer.StreamRequestHandler.finish(self)
    
    #@-node:finish
    #@-others
#@-node:class KNodeReqHandler
#@+node:class KNodeClient
class KNodeClient(KBase):
    """
    Talks to a KNodeServer over a socket
    
    Subclass this to implement Stasher clients in Python
    """
    #@    @+others
    #@+node:__init__
    def __init__(self, address=clientAddr):
    
        if type(address) in [type(()), type([])]:
            self.host, self.port = clientAddr
        else:
            self.host, self.port = clientAddr.split(":")
            self.port = int(self.port)
    
        self.hello()
    
    #@-node:__init__
    #@+node:hello
    def hello(self):
        
        self.connect()
        self.close()
    #@-node:hello
    #@+node:connect
    def connect(self):
    
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))
    
        self.rfile = self.sock.makefile("rb")
        self.read = self.rfile.read
        self.readline = self.rfile.readline
        self.wfile = self.sock.makefile("wb")
        self.write = self.wfile.write
        self.flush = self.wfile.flush
    
        # read greeting
        greeting = self.readline()
        parts = re.split("\\s+", greeting)
        if parts[0] != "Stasher":
            self.close()
            raise Exception("Not connected to valid stasher interface")
    
    #@-node:connect
    #@+node:close
    def close(self):
        
        self.rfile.close()
        #self.wfile.close()
        self.sock.close()
    
    #@-node:close
    #@+node:get
    def get(self, key):
        """
        sends a get command to stasher socket, and retrieves
        and interprets result
        """
        self.connect()
        
        self.write("get %s\n" % key)
        self.flush()
    
        #print "waiting for resp line"
        res = self.readline().strip()
    
        if res == "ok":
            size = int(self.readline())
            val = self.read(size)
            self.close()
            return val
        else:
            self.close()
            return None
    
    #@-node:get
    #@+node:put
    def put(self, key, val):
        """
        Tells remote stasher port to insert a file into the network
        """
        self.connect()
        self.write("put %s\n" % key)
        self.write("%s\n" % len(val))
        self.write(val)
        self.flush()
        
        res = self.readline().strip()
    
        self.close()
    
        if res == "ok":
            return True
        else:
            print repr(res)
            return False
    
    #@-node:put
    #@+node:addref
    def addref(self, ref):
        """
        Passes a new noderef to node
        """
        self.connect()
        self.write("addref %s\n" % ref)
        self.flush()
        
        res = self.readline().strip()
    
        self.close()
    
        if res == "ok":
            return True
        else:
            print repr(res)
            return False
    
    #@-node:addref
    #@+node:getref
    def getref(self):
        """
        Uplifts node's own ref
        """
        self.connect()
        self.write("getref\n")
        self.flush()
        
        res = self.readline().strip()
    
        if res == "ok":
            ref = self.readline().strip()
            self.close()
            return ref
        else:
            self.close()
            return "failed"
    
    #@-node:getref
    #@+node:pingall
    def pingall(self):
        """
        Uplifts node's own ref
        """
        self.connect()
        self.write("pingall\n")
        self.flush()
        
        res = self.readline().strip()
    
        self.close()
    
        return res
    
    
    #@-node:pingall
    #@+node:kill
    def kill(self):
        """
        Tells remote server to fall on its sword
        """
        try:
            while 1:
                self.connect()
                self.write("die\n")
                self.flush()
                self.close()
        except:
            pass
    
    
    #@-node:kill
    #@+node:__getitem__
    def __getitem__(self, item):
        
        return self.get(item)
    
    #@-node:__getitem__
    #@+node:__setitem__
    def __setitem__(self, item, val):
        
        if not self.put(item, val):
            raise Exception("Failed to insert")
    
    #@-node:__setitem__
    #@-others

#@-node:class KNodeClient
#@-node:Node Socket Server
#@+node:NODE
#@+node:class KNode
class KNode(KBase):
    """
    B{Public API to this Kademlia implementation}
    
    You should not normally need to use, or even be aware of, 
    any of the other classes

    And in this class, the only methods you need to worry about are:
        - L{start} - starts the node running
        - L{stop} - stops the node
        - L{get} - retrieve a key value
        - L{put} - stores a key value
        - L{addref} - imports a noderef

    This class implements a single kademlia node.
    Within a single process, you can create as many nodes as you like.
    """
    #@    @+others
    #@+node:attributes
    SocketFactory = None # defaults to I2P socket
    
    #@-node:attributes
    #@+node:__init__
    def __init__(self, name, **kw):
        """
        Creates a kademlia node of name 'name'.
    
        Name is mandatory, because each name is permanently written
        to the SAM bridge's store
        
        I thought of supporting random name generation, but went off this
        idea because names get permanently stored to SAM bridge's file
    
        Arguments:
            - name - mandatory - a short text name for the node, should
              be alphanumerics, '-', '.', '_'
              This name is used for the SAM socket session.
        
        Keywords:
            - storage - optional - an instance of L{KStorageBase} or one of
              its subclasses. If not given, default action is to instantiate
              a L{KStorageFile} object against the given node name
        """
        # remember who we are
        self.name = name
    
        # not running yet, will launch when explicitly started, or implicitly
        # when the first operation gets done
        self.isRunning = False
    
        # create socket and get its dest, and determine our node id
        self.id = KHash("<NONE>")
        self.log(5, "creating socket for node %s" % name)
        self.log(5, "socket for node %s created" % name)
        if self.SocketFactory == None:
            self.SocketFactory = i2p.socket.socket
        self.sock = self.SocketFactory(
            "stashernode-"+name,
            i2p.socket.SOCK_DGRAM,
            samaddr=samAddr,
            **kw)
        #self.sockLock = threading.Lock() # prevents socket API reentrance
        self.sock.setblocking(0)
        self.dest = self.sock.dest
        self.id = KHash(self.dest)
    
        # create our buckets
        self.buckets = []
        for i in range(160):
            self.buckets.append(KBucket())
        
        # create our storage object, default to new instance of KStorageFile
        self.storage = kw.get('storage', KStorageFile(self))
    
        # dig out all previously known nodes
        self.peers = self.storage.getRefs()
    
        # set up dict of callers awaiting replies
        # keys are (peerobj, msgId) tuples, values are Queue.Queue objects
        self.pendingPings = {}
    
        # mapping of (peer, msgId) to RPC object, so when RPC replies come in,
        # they can be passed directly to the RPC object concerned
        self.rpcBindings = {}
    
        # KRpc objects waiting for peer replies - used for checking for timeouts
        self.rpcPending = []
        
        # miscellaneous shit
        self._msgIdNext = 0
        #self._msgIdLock = threading.Lock()
    
        # register in global map
        _nodes[name] = self
    
    
    #@-node:__init__
    #@+node:__del__
    def __del__(self):
        """
        Cleanup
        """
    
    #@-node:__del__
    #@+node:application-level
    #@+node:start
    def start(self, doPings=True):
        """
        Starts the node running
        """
        # barf if already running
        if self.isRunning:
            self.log(3, "node %s is already running!" % self.name)
            return
    
        self.log(3, "starting node %s" % self.name)
    
        # first step - ping all our peers
        if doPings:
            for peer in self.peers:
                self.log(3, "doing initial ping\n%s\n%s" % (self, peer))
                KRpcPing(self, peer=peer)
    
        # first step - do a findNode against our own node id, and ping our
        # neighbours
        if greetPeersOnStartup:
            neighbours = KRpcFindNode(self, hash=self.id).execute()
            self.log(3, "neighbours=%s" % repr([n[:10] for n in neighbours]))
            for n in neighbours:
                n = self._normalisePeer(n)
                KRpcPing(self, peer=n)
    
        # note now that we're running
        self.isRunning = True
    
        # and enlist with the core
        if runCore:
            core.subscribe(self)
        else:
            # central core disabled, run our own receiver thread instead
            thread.start_new_thread(self._threadRx, ())
    #@-node:start
    #@+node:stop
    def stop(self):
        """
        Shuts down the node
        """
        self.isRunning = 0
        if runCore:
            try:
                core.unsubscribe(self)
            except:
                pass
    #@-node:stop
    #@+node:get
    def get(self, item, callback=None, **kw):
        """
        Attempts to retrieve data from the network
        
        Arguments:
            - item - the key we desire
            - callback - optional - if given, the get will be performed
              asynchronously, and callback will be invoked upon completion, with
              the result as first argument
        Keywords:
            - local - optional - if True, limits this search to this local node
              default is False
        
        Returns:
            - if no callback - the item value if the item was found, or None if not
            - if callback, None is returned
        """
        def processResult(r):
            if isinstance(r, str):
                return r
            return None
    
        if callback:
            # create a func to process callback result
            def onCallback(res):
                callback(processResult(res))
        
            self._finddata(item, onCallback, **kw)
        else:
            return processResult(self._finddata(item, **kw))
    
    #@-node:get
    #@+node:put
    def put(self, key, value, callback=None, **kw):
        """
        Inserts a named key into the network
    
        Arguments:
            - key - one of:
                - None - a secure key will be generated and used
                - a KHash object
                - a raw string which will be hashed into a KHash object
            - val - a string, the value associated with the key
    
        If the value is larger than L{maxValueSize}, a L{KValueTooLarge}
        exception will occur.
        """
        return self._store(key, value, callback, **kw)
    
    #@-node:put
    #@+node:addref
    def addref(self, peer, doPing=False):
        """
        Given a peer node's destination, add it to our
        buckets and internal data store
        
        Arguments:
            - peer - one of:
                - the I2P destination of the peer node, as
                  a base64 string
                - a KNode object
                - a KPeer object
            - doPing - ping this node automatically (default False)
        """
        peer = self._normalisePeer(peer)
    
        # remember peer if not already known
        if peer.dest == self.dest:
            self.log(3, "node %s, trying to add ref to ourself???" % self.name)
            return peer
        elif not self._findPeer(peer.dest):
            self.peers.append(peer)
            self.storage.putRefs(peer)
        else:
            self.log(4, "node %s, trying to add duplicate noderef %s" % (
                self.name, peer))
            return peer
    
        # update our KBucket
        dist = self.id.distance(peer.id)
        self.buckets[dist].justSeenPeer(peer)
    
        if doPing:
            self.log(4, "doing initial ping\n%s\n%s" % (self, peer))
            KRpcPing(self, peer=peer)
    
        return peer
    
    #@-node:addref
    #@+node:__getitem__
    def __getitem__(self, item):
        """
        Allows dict-like accesses on the node object
        """
        return self.get(item)
    #@-node:__getitem__
    #@+node:__setitem__
    def __setitem__(self, item, val):
        """
        Allows dict-like key setting on the node object
        """
        self.put(item, val)
    
    #@-node:__setitem__
    #@-node:application-level
    #@+node:peer/rpc methods
    #@+node:_ping
    def _ping(self, peer=None, callback=None, **kw):
        """
        Sends a ping to remote peer, and awaits response
        
        Not of much real use to application level, except
        perhaps for testing
    
        If the argument 'peer' is not given, the effect is to 'ping the
        local node', which I guess might be a bit silly
    
        The second argument 'callback' is a callable, which if given, makes this
        an asynchronous (non-blocking) call, in which case the callback will be
        invoked upon completion (or timeout).
        
        If the keyword 'cbArgs' is given in addition to the callback, the callback
        will fire with the results as first argument and this value as second arg
        """
        if callback:
            KRpcPing(self, callback, peer=peer, **kw)
        else:
            return KRpcPing(self, peer=peer).execute()
    
    #@-node:_ping
    #@+node:_pingall
    def _pingall(self, callback=None):
        """
        Sends a ping to all peers, returns text string on replies/failures
        """
        if callback:
            KRpcPingAll(self, callback, **kw)
        else:
            return KRpcPingAll(self).execute()
    
       
    #@-node:_pingall
    #@+node:_findnode
    def _findnode(self, something=None, callback=None, **kw):
        """
        Mainly for testing - does a findNode query on the network
        
        Arguments:
            - something - one of:
                - plain string - the string gets hashed and used for the search
                - int or long int - this gets used as the raw hash
                - a KHash object - that's what gets used
                - None - the value of the 'raw' keyword will be used instead
            - callback - optional - if given, a callable object which will be
              called upon completion, with the result as argument
    
        Keywords:
            - local - optional - if True, only returns the closest peers known to
              node. if False, causes node to query other nodes.
              default is False
            - raw - one of:
                - 20-byte string - this gets used as a binary hash
                - 40-byte string - this gets used as a hex hash
        """
        if not kw.has_key('local'):
            kw = dict(kw)
            kw['local'] = False
    
        self.log(3, "about to instantiate findnode rpc")
        if callback:
            KRpcFindNode(self, callback, hash=something, **kw)
            self.log(3, "asynchronously invoked findnode, expecting callback")
        else:
            lst = KRpcFindNode(self, hash=something, **kw).execute()
            self.log(3, "back from findnode rpc")
            res = [self._normalisePeer(p) for p in lst] # wrap in KPeer objects
            return res
    
    #@-node:_findnode
    #@+node:_finddata
    def _finddata(self, something=None, callback=None, **kw):
        """
        As for findnode, but if data is found, return the data instead
        """
        if not kw.has_key('local'):
            kw = dict(kw)
            kw['local'] = False
    
        self.log(3, "about to instantiate finddata rpc")
        if callback:
            KRpcFindData(self, callback, hash=something, **kw)
            self.log(3, "asynchronously invoked finddata, expecting callback")
        else:
            res = KRpcFindData(self, hash=something, **kw).execute()
            self.log(3, "back from finddata rpc")
            if not isinstance(res, str):
                self.log(4, "findData RPC returned %s" % repr(res))
                res = [self._normalisePeer(p) for p in res] # wrap in KPeer objects
            return res
    
    #@-node:_finddata
    #@+node:_store
    def _store(self, key, value, callback=None, **kw):
        """
        Performs a STORE rpc
        
        Arguments:
            - key - string - text name of key
            - value - string - value to store
    
        Keywords:
            - local - if given and true, only store value onto local store
        """
        if not kw.has_key('local'):
            kw = dict(kw)
            kw['local'] = False
    
        key = shahash(key)
        if callback:
            KRpcStore(self, callback, key=key, value=value, **kw)
            self.log(3, "asynchronously invoked findnode, expecting callback")
        else:
            res = KRpcStore(self, key=key, value=value, **kw).execute()
            return res
    
    #@-node:_store
    #@+node:_findPeer
    def _findPeer(self, dest):
        """
        Look up our table of current peers for a given dest.
        
        If dest is found, return its object, otherwise return None
        """
        for peerObj in self.peers:
            if peerObj.dest == dest:
                return peerObj
        return None
    
    #@-node:_findPeer
    #@-node:peer/rpc methods
    #@+node:comms methods
    #@+node:_sendRaw
    def _sendRaw(self, peer, **kw):
        """
        Serialises keywords passed, and sends this as a datagram
        to node 'peer'
        """
        # update our KBucket
        dist = self.id.distance(peer.id)
        self.buckets[dist].justSeenPeer(peer)
    
        # save ref to this peer
        self.addref(peer)
    
        params = dict(kw)
        msgId = params.get('msgId', None)
        if msgId == None:
            msgId = params['msgId'] = self._msgIdAlloc()
    
        objenc = messageEncode(params)
        self.log(5, "node %s waiting for send lock" % self.name)
        #self.sockLock.acquire()
        self.log(5, "node %s got send lock" % self.name)
        try:
            self.sock.sendto(objenc, 0, peer.dest)
        except:
            traceback.print_exc()
        #self.sockLock.release()
        self.log(5, "node %s released send lock" % self.name)
    
        self.log(4, "node %s sent %s to peer %s" % (self.name, params, peer.dest))
        return msgId
    
    #@-node:_sendRaw
    #@-node:comms methods
    #@+node:engine
    #@+node:_threadRx
    def _threadRx(self):
        """
        Thread which listens for incoming datagrams and actions
        accordingly
        """
        self.log(3, "starting receiver thread for node %s" % self.name)
    
        try:
            # loop to drive the node
            while self.isRunning:
                self._doChug()
        except:
            traceback.print_exc()
            self.log(3, "node %s - THREAD CRASH!" % self.name)
    
        self.log(3, "receiver thread for node %s terminated" % self.name)
    
    #@-node:_threadRx
    #@+node:_doChug
    def _doChug(self):
        """
        Do what's needed to drive the node.
        Handle incoming packets
        Check on and action timeouts
        """
        # handle all available packets
        while self._doRx():
            pass
    
        # do maintenance - eg processing timeouts
        self._doHousekeeping()
    
    #@-node:_doChug
    #@+node:_doRx
    def _doRx(self):
        """
        Receives and handles one incoming packet
        
        Returns True if a packet got handled, or False if timeout
        """
        # get next packet
        self.log(5, "%s seeking socket lock" % self.name)
        #self.sockLock.acquire()
        self.log(5, "%s got socket lock" % self.name)
        try:
            item = self.sock.recvfrom(-1)
        except i2p.socket.BlockError:
            #self.sockLock.release()
            self.log(5, "%s released socket lock after timeout" % self.name)
            if not runCore:
                time.sleep(0.1)
            return False
        except:
            traceback.print_exc()
            self.log(5, "%s released socket lock after exception" % self.name)
            #self.sockLock.release()
            return True
        #self.sockLock.release()
        self.log(5, "%s released socket lock normally" % self.name)
    
        try:
            (data, dest) = item
        except ValueError:
            self.log(3, "node %s: recvfrom returned no dest, possible spoof" \
                % self.name)
            data = item[0]
            dest = None
    
        # try to decode
        try:
            d = messageDecode(data)
        except:
            traceback.print_exc()
            self.log(3, "failed to unpickle incoming data for node %s" % \
                self.name)
            return True
    
        # ditch if not a dict
        if type(d) != type({}):
            self.log(3, "node %s: decoded packet is not a dict" % self.name)
            return True
    
        # temporary workaround for sam socket bug    
        if dest == None:
            if hasattr(d, 'has_key') and d.has_key('dest'):
                dest = d['dest']
    
        # try to find it in our store
        peerObj = self._findPeer(dest)
        if peerObj == None:
            # previously unknown peer - add it to our store
            peerObj = self.addref(dest)
        else:
            peerObj.justSeen() # already exists - refresh its timestamp
            self.addref(peerObj.dest)
    
        # drop packet if no msgId
        msgId = d.get('msgId', None)
        if msgId == None:
            self.log(3, "no msgId, dropping")
            return True
        del d['msgId']
    
        msgType = d.get('type', 'unknown')
    
        if desperatelyDebugging:
            pass
            #set_trace()
    
        # if a local RPC is awaiting this message, fire its callback
        item = self.rpcBindings.get((peerObj.dest, msgId), None)
        if item:
            rpc, peer = item
            try:
                rpc.unbindPeerReply(peerObj, msgId)
                if desperatelyDebugging:
                    set_trace()
                rpc.on_reply(peerObj, msgId, **d)
    
            except:
                traceback.print_exc()
                self.log(2, "unhandled exception in RPC on_reply")
        else:
            # find a handler, fallback on 'unknown'
            self.log(5, "\nnode %s\ngot msg id %s type %s:\n%s" % (
                self.name, msgId, msgType, d))
            hdlrName = d.get('type', 'unknown')
            hdlr = getattr(self, "_on_"+hdlrName)
            try:
                if desperatelyDebugging:
                    set_trace()
                hdlr(peerObj, msgId, **d)
            except:
                traceback.print_exc()
                self.log(2, "unhandled exception in unbound packet handler %s" % hdlrName)
    
        return True
    
    #@-node:_doRx
    #@+node:_doHousekeeping
    def _doHousekeeping(self):
        """
        Performs periodical housekeeping on this node.
        
        Activities include:
            - checking pending records for timeouts
        """
        now = time.time()
    
        # DEPRECATED - SWITCH TO RPC-based
        # check for expired pings
        for msgId, (dest, q, pingDeadline) in self.pendingPings.items():
    
            if pingDeadline > now:
                # not timed out, leave in pending
                continue
    
            # ping has timed out
            del self.pendingPings[msgId]
            q.put(False)
    
        # check for timed-out RPCs
        for rpc in self.rpcPending[:]:
            if rpc.nextTickTime != None and now >= rpc.nextTickTime:
                try:
                    rpc.on_tick()
                except:
                    traceback.print_exc()
                    self.log(2, "unhandled exception in RPC on_tick")
    
    #@-node:_doHousekeeping
    #@-node:engine
    #@+node:event handling
    #@+others
    #@+node:_on_ping
    def _on_ping(self, peer, msgId, **kw):
        """
        Handler for ping received events
        """
        KRpcPing(self, (peer, msgId), local=True, **kw)
        return
        
        # old stuff
    
        self.log(3, "\nnode %s\nfrom %s\nreceived:\n%s" % (self.name, peer, kw))
    
        # randomly cause ping timeouts if testing
        if testing:
            howlong = random.randint(0, 5)
            self.log(3, "deliberately pausing for %s seconds" % howlong)
            time.sleep(howlong)
    
        # pong back to node
        peer.send_reply(msgId=msgId)
    
        
    #@nonl
    #@-node:_on_ping
    #@+node:_on_findNode
    def _on_findNode(self, peer, msgId, **kw):
        """
        Handles incoming findNode command
        """
        KRpcFindNode(self, (peer, msgId), local=True, **kw)
    
    #@-node:_on_findNode
    #@+node:_on_findData
    def _on_findData(self, peer, msgId, **kw):
        """
        Handles incoming findData command
        """
        KRpcFindData(self, (peer, msgId), local=True, **kw)
    
    #@-node:_on_findData
    #@+node:_on_store
    def _on_store(self, peer, msgId, **kw):
        """
        Handles incoming STORE command
        """
        self.log(4, "got STORE rpc from upstream:\npeer=%s\nmsgId=%s\nkw=%s" % (peer, msgId, kw))
    
        KRpcStore(self, (peer, msgId), local=True, **kw)
    
    #@-node:_on_store
    #@+node:_on_reply
    def _on_reply(self, peer, msgId, **kw):
        """
        This should never happen
        """
        self.log(4, "got unhandled reply:\npeer=%s\nmsgId=%s\nkw=%s" % (
            peer, msgId, kw))
    
    #@-node:_on_reply
    #@+node:_on_unknown
    def _on_unknown(self, peer, msgId, **kw):
        """
        Handler for unknown events
        """
        self.log(3, "node %s from %s received msgId=%s:\n%s" % (
            self.name, peer, msgId, kw))
    
    #@-node:_on_unknown
    #@-others
    #@-node:event handling
    #@+node:Socket Client Server
    #@+node:serve
    def serve(self):
        """
        makes this node listen on socket for incoming client
        connections, and services these connections
        """
        server = KNodeServer(self)
        server.serve_forever()
    
    #@-node:serve
    #@-node:Socket Client Server
    #@+node:lowlevel stuff
    #@+others
    #@+node:__str__
    def __str__(self):
        return "<KNode:%s=0x%s...>" % (
            self.name,
            ("%x" % self.id.value)[:8],
            )
    #@-node:__str__
    #@+node:__repr__
    def __repr__(self):
        return str(self)
    
    #@-node:__repr__
    #@+node:_msgIdAlloc
    def _msgIdAlloc(self):
        """
        issue a new and unique message id
        """
        #self._msgIdLock.acquire()
        msgId = self._msgIdNext
        self._msgIdNext += 1
        #self._msgIdLock.release()
        return msgId
    #@-node:_msgIdAlloc
    #@+node:_normalisePeer
    def _normalisePeer(self, peer):
        """
        Takes either a b64 dest string, a KPeer object or a KNode object,
        and returns a KPeer object
        """
        # act according to whatever type we're given
        if isinstance(peer, KPeer):
            return peer # already desired format
        elif isinstance(peer, KNode):
            return KPeer(self, peer.dest)
        elif isinstance(peer, str) and len(peer) > 256:
            return KPeer(self, peer)
        else:
            self.log(3, "node %s, trying to add invalid noderef %s" % (
                self.name, peer))
            raise KBadNode(peer)
    
    #@-node:_normalisePeer
    #@+node:__del__
    def __del__(self):
        """
        Clean up on delete
        """
        self.log(3, "node dying: %s" % self.name)
    
        try:
            del _nodes[self.name]
        except:
            pass
    
        self.stop()
    
    #@-node:__del__
    #@-others
    #@-node:lowlevel stuff
    #@-others
#@-node:class KNode
#@-node:NODE
#@+node:funcs
#@+others
#@+node:userI2PDir
def userI2PDir(nodeName=None):
    """
    Returns a directory under user's home dir into which
    stasher files can be written

    If nodename is given, a subdirectory will be found/created
    
    Return value is toplevel storage dir if nodename not given, 
    otherwise absolute path including node dir
    """
    if dataDir != None:
        if not os.path.isdir(dataDir):
            os.makedirs(dataDir)
        return dataDir

    if sys.platform == 'win32':
        home = os.getenv("APPDATA")
        if home:
            topDir = os.path.join(home, "stasher")
        else:
            topDir = os.path.join(os.getcwd(), "stasher")
    else:
        #return os.path.dirname(__file__)
        topDir = os.path.join(os.path.expanduser('~'), ".stasher")

    if not os.path.isdir(topDir):
        os.makedirs(topDir)
    if nodeName == None:
        return topDir
    else:
        nodeDir = os.path.join(topDir, nodeName)
        if not os.path.isdir(nodeDir):
            os.makedirs(nodeDir)
        return nodeDir

#@-node:userI2PDir
#@+node:nodePidfile
def nodePidfile(nodename):
    return os.path.join(userI2PDir(nodename), "node.pid")

#@-node:nodePidfile
#@+node:messageEncode
def messageEncode(params):
    """
    Serialise the dict 'params' for sending
    
    Temporarily using bencode - replace later with a more
    efficient struct-based impl.
    """
    try:
        return bencode.bencode(params)
    except:
        log(1, "encoder failed to encode: %s" % repr(params))
        raise

#@-node:messageEncode
#@+node:messageDecode
def messageDecode(raw):
    return bencode.bdecode(raw)
#@-node:messageDecode
#@+node:shahash
def shahash(somestr, bin=False):
    shaobj = sha.new(somestr)
    if bin:
        return shaobj.digest()
    else:
        return shaobj.hexdigest()

#@-node:shahash
#@+node:log
logLock = threading.Lock()

def log(verbosity, msg, nPrev=0, clsname=None):

    global logToSocket, logFile

    # create logfile if not exists
    if logFile == None:
        logFile = os.path.join(userI2PDir(), "stasher.log")

    # rip the stack
    caller = traceback.extract_stack()[-(2+nPrev)]
    path, line, func = caller[:3]
    path = os.path.split(path)[1]

    #print "func is type %s, val %s" % (type(func), repr(func))

    #if hasattr(func, "im_class"):
    #    func = 

    if clsname:
        func = clsname + "." + func

    #msg = "%s:%s:%s():  %s" % (
    #    path,
    #    line,
    #    func,
    #    msg.replace("\n", "\n   + "))

    msg = "%s():%s:  %s" % (
        func,
        line,
        msg.replace("\n", "\n   + "))

    # do better logging later
    if verbosity > logVerbosity:
        return

    if logToSocket:
        try:
            if isinstance(logToSocket, int):
                portnum = logToSocket
                logToSocket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                connected = 0
                while 1:
                    try:
                        logToSocket.connect(("localhost", portnum))
                        break
                    except socket.error:
                        print "Please open an xterm/nc listening on %s" % logToSocket
                        time.sleep(1)

            logToSocket.send(msg+"\n")
        except:
            traceback.print_exc()
    else:
        print msg

    logLock.acquire()
    file(logFile, "a+").write(msg + "\n")
    logLock.release()
#@-node:log
#@+node:logexc
def logexc(verbosity, msg, nPrev=0, clsname=None):

    fd = StringIO("%s\n" % msg)
    traceback.print_exc(file=fd)
    log(verbosity, fd.getvalue(), nPrev, clsname)

#@-node:logexc
#@+node:spawnproc
def spawnproc(*args, **kw):
    """
    Spawns a process and returns its PID
    
    VOMIT!
    
    I have to do a pile of odious for the win32 side
    
    Returns a usable PID
    
    Keywords:
        - priority - priority at which to spawn - default 20 (highest)
    """
    # get priority, convert to a unix 'nice' value
    priority = 20 - kw.get('priority', 20)
    
    if sys.platform != 'win32':
        # *nix - easy
        #print "spawnproc: launching %s" % repr(args)
        
        # insert nice invocation
        args = ['/usr/bin/nice', '-n', str(priority)] + list(args)
        return os.spawnv(os.P_NOWAIT, args[0], args)

    else:
        # just close your eyes here and pretend this abomination isn't happening! :((
        args = list(args)
        args.insert(0, sys.executable)
        cmd = " ".join(args)
        #print "spawnproc: launching %s" % repr(cmd)

        if 0:
            try:
                c =  _winreg.ConnectRegistry(None, _winreg.HKEY_LOCAL_MACHINE)
                c1 = _winreg.OpenKey(c, "SOFTWARE")
                c2 = _winreg.OpenKey(c1, "Microsoft")
                c3 = _winreg.OpenKey(c2, "Windows NT")
                c4 = _winreg.OpenKey(c3, "CurrentVersion")
                supportsBelowNormalPriority = 1
            except:
                supportsBelowNormalPriority = 0
        else:
            if sys.getwindowsversion()[3] != 2:
                supportsBelowNormalPriority = 0
            else:
                supportsBelowNormalPriority = 1
    
        # frig the priority into a windows value
        if supportsBelowNormalPriority:
            if priority < 7:
                pri = win32process.IDLE_PRIORITY_CLASS
            elif priority < 14:
                pri = 0x4000
            else:
                pri = win32process.NORMAL_PRIORITY_CLASS
        else:
            if priority < 11:
                pri = win32process.IDLE_PRIORITY_CLASS
            else:
                pri = win32process.NORMAL_PRIORITY_CLASS
    
        print "spawnproc: launching %s" % repr(args)
        si = win32process.STARTUPINFO()
        hdl = win32process.CreateProcess(
            sys.executable, # lpApplicationName
            cmd,  # lpCommandLine
            None,  # lpProcessAttributes
            None, # lpThreadAttributes
            0,    # bInheritHandles
            0,    # dwCreationFlags
            None, # lpEnvironment
            None, # lpCurrentDirectory
            si,   # lpStartupInfo
            )
        pid = hdl[2]
        #print "spawnproc: pid=%s" % pid
        return pid
#@-node:spawnproc
#@+node:killproc
def killproc(pid):
    if sys.platform == 'win32':
        print repr(pid)
        handle = win32api.OpenProcess(1, 0, pid)
        print "pid %s -> %s" % (pid, repr(handle))
        #return (0 != win32api.TerminateProcess(handle, 0))
        win32process.TerminateProcess(handle, 0)
    else:
        return os.kill(pid, signal.SIGKILL)
#@-node:killproc
#@+node:i2psocket
def i2psocket(self, *args, **kw):
    return i2p.socket.socket(*args, **kw)

#@-node:i2psocket
#@+node:usage
def usage(detailed=False, ret=0):
    
    print "Usage: %s <options> [<command> [<ars>...]]" % sys.argv[0]
    if not detailed:
        print "Type %s -h for help" % sys.argv[0]
        sys.exit(ret)

    print "This is stasher, distributed file storage network that runs"
    print "atop the anonymising I2P network (http://www.i2p.net)"
    print "Written by aum - August 2004"
    print
    print "Options:"
    print "  -h, --help              - display this help"
    print "  -v, --version           - print program version"
    print "  -V, --verbosity=n       - verbosity, default 1, 1=quiet ... 4=noisy"
    print "  -S, --samaddr=host:port - host:port of I2P SAM port, "
    print "                            default %s" % i2p.socket.samaddr
    print "  -C, --clientaddr=host:port - host:port for socket interface to listen on"
    print "                            for clients, default %s" % clientAddr
    print "  -d, --datadir=dir       - directory in which stasher files get written"
    print "                            default is ~/.i2pstasher"
    print "  -f, --foreground        - only valid for 'start' cmd - runs the node"
    print "                            in foreground without spawning - for debugging"
    print
    print "Commands:"
    print "  start [<nodename>]"
    print "    - launches a single node, which forks off and runs in background"
    print "      nodename is a short unique nodename, default is '%s'" % defaultNodename
    print "  stop [<nodename>]"
    print "    - terminates running node <nodename>"
    print "  get <keyname> [<file>]"
    print "    - attempts to retrieve key <keyname> from the network, saving"
    print "      to file <file> if given, or to stdout if not"
    print "  put <keyname> [<file>]"
    print "    - inserts key <keyname> into the network, taking its content"
    print "      from file <file> if given, otherwise reads content from stdin"
    print "  addref <file>"
    print "    - adds a new noderef to the node, taking the base64 noderef"
    print "      from file <file> if given, or from stdin"
    print "      (if you don't have any refs, visit http://stasher.i2p, or use"
    print "      the dest in the file aum.stasher in cvs)"
    print "  getref <file>"
    print "    - uplifts the running node's dest as base64, writing it to file"
    print "      <file> if given, or to stdout"
    print "  hello"
    print "    - checks that local node is running"
    print "  pingall"
    print "    - diagnostic tool - pings all peers, waits for replies or timeouts,"
    print "      reports results"
    print "  help"
    print "    - display this help"
    print

    sys.exit(0)

#@-node:usage
#@+node:err
def err(msg):
    sys.stderr.write(msg+"\n")
#@-node:err
#@+node:main
def main():
    """
    Command line interface
    """
    global samAddr, clientAddr, logVerbosity, dataDir

    argv = sys.argv
    argc = len(argv)

    try:
        opts, args = getopt.getopt(sys.argv[1:],
                                   "h?vV:S:C:sd:f",
                                   ['help', 'version', 'samaddr=', 'clientaddr=',
                                    'verbosity=', 'status', 'datadir=', 'foreground',
                                    'shortversion',
                                    ])
    except:
        traceback.print_exc(file=sys.stdout)
        usage("You entered an invalid option")

    daemonise = True
    verbosity = 2
    debug = False
    foreground = False

    for opt, val in opts:
        
        if opt in ['-h', '-?', '--help']:
            usage(True)

        elif opt in ['-v', '--version']:
            print "Stasher version %s" % version
            sys.exit(0)

        elif opt in ['-V', '--verbosity']:
            logVerbosity = int(val)

        elif opt in ['-f', '--foreground']:
            foreground = True
        
        elif opt in ['-S', '--samaddr']:
            samAddr = val

        elif opt in ['-C', '--clientaddr']:
            clientAddr = val
        
        elif opt in ['-s', '--status']:
            dumpStatus()

        elif opt in ['-d', '--datadir']:
            dataDir = val

        elif opt == '--shortversion':
            sys.stdout.write("%s" % version)
            sys.stdout.flush()
            sys.exit(0)

    #print "Debug - bailing"
    #print repr(opts)
    #print repr(args)
    #sys.exit(0)

    # Barf if no command given
    if len(args) == 0:
        err("No command given")
        usage(0, 1)

    cmd = args.pop(0)
    argc = len(args)

    #print "cmd=%s, args=%s" % (repr(cmd), repr(args))
    
    if cmd not in ['help', '_start', 'start', 'stop',
                   'hello', 'get', 'put', 'addref', 'getref',
                   'pingall']:
        err("Illegal command '%s'" % cmd)
        usage(0, 1)

    if cmd == 'help':
        usage()

    # dirty hack
    if foreground and cmd == 'start':
        cmd = '_start'

    # magic undocumented command name - starts node, launches its client server,
    # this should only happen if we're spawned from a 'start' command
    if cmd == '_start':
        if argc not in [0, 1]:
            err("start: bad argument count")
            usage()
        if argc == 0:
            nodeName = defaultNodename
        else:
            nodeName = args[0]
        
        # create and serve a node
        #set_trace()
        node = KNode(nodeName)
        node.start()
        log(3, "Node %s launched, dest = %s" % (node.name, node.dest))
        node.serve()
        sys.exit(0)
    
    if cmd == 'start':
        if argc not in [0, 1]:
            err("start: bad argument count")
            usage()
        if argc == 0:
            nodeName = defaultNodename
        else:
            nodeName = args[0]
        pidFile = nodePidfile(nodeName)

        if os.path.exists(pidFile):
            err(("Stasher node '%s' seems to be already running. If you are\n" % nodeName)
                +"absolutely sure it's not running, please remove its pidfile:\n"
                +pidFile+"\n")
            sys.exit(1)

        # spawn off a node
        import stasher
        pid = spawnproc(sys.argv[0], "-S", samAddr, "-C", clientAddr, "_start", nodeName)
        file(pidFile, "wb").write("%s" % pid)
        print "Launched stasher node as pid %s" % pid
        print "Pidfile is %s" % pidFile
        sys.exit(0)

    if cmd == 'stop':
        if argc not in [0, 1]:
            err("stop: bad argument count")
            usage()
        if argc == 0:
            nodeName = defaultNodename
        else:
            nodename = args[0]

        pidFile = nodePidfile(nodeName)

        if not os.path.isfile(pidFile):
            err("Stasher node '%s' is not running - cannot kill\n" % nodeName)
            sys.exit(1)

        pid = int(file(pidFile, "rb").read())
        try:
            killproc(pid)
            print "Killed stasher node (pid %s)" % pid
        except:
            print "Failed to kill node (pid %s)" % pid
        os.unlink(pidFile)
        sys.exit(0)

    try:
        client = KNodeClient()
    except:
        traceback.print_exc()
        err("Node doesn't seem to be up, or reachable on %s" % clientAddr)
        return


    if cmd == 'hello':
        err("Node seems fine")
        sys.exit(0)
    
    elif cmd == 'get':
        if argc not in [1, 2]:
            err("get: bad argument count")
            usage()

        key = args[0]

        if argc == 2:
            # try to open output file
            path = args[1]
            try:
                outfile = file(path, "wb")
            except:
                err("Cannot open output file %s" % repr(path))
                usage(0, 1)
        else:
            outfile = sys.stdout

        res = client.get(key)
        if res == None:
            err("Failed to retrieve '%s'" % key)
            sys.exit(1)
        else:
            outfile.write(res)
            outfile.flush()
            outfile.close()
            sys.exit(0)

    elif cmd == 'put':
        if argc not in [1, 2]:
            err("put: bad argument count")
            usage()

        key = args[0]

        if argc == 2:
            # try to open input file
            path = args[1]
            try:
                infile = file(path, "rb")
            except:
                err("Cannot open input file %s" % repr(path))
                usage(0, 1)
        else:
            infile = sys.stdin

        val = infile.read()
        if len(val) > maxValueSize:
            err("File is too big - please trim to %s" % maxValueSize)

        res = client.put(key, val)
        if res == None:
            err("Failed to insert '%s'" % key)
            sys.exit(1)
        else:
            sys.exit(0)

    elif cmd == 'addref':
        if argc not in [0, 1]:
            err("addref: bad argument count")
            usage()

        if argc == 1:
            # try to open input file
            path = args[0]
            try:
                infile = file(path, "rb")
            except:
                err("Cannot open input file %s" % repr(path))
                usage(0, 1)
        else:
            infile = sys.stdin

        ref = infile.read()

        res = client.addref(ref)
        if res == None:
            err("Failed to add ref")
            sys.exit(1)
        else:
            sys.exit(0)

    elif cmd == 'getref':
        if argc not in [0, 1]:
            err("getref: bad argument count")
            usage()

        res = client.getref()

        if argc == 1:
            # try to open output file
            path = args[0]
            try:
                outfile = file(path, "wb")
            except:
                err("Cannot open output file %s" % repr(path))
                usage(0, 1)
        else:
            outfile = sys.stdout

        if res == None:
            err("Failed to retrieve node ref")
            sys.exit(1)
        else:
            outfile.write(res)
            outfile.flush()
            outfile.close()
            sys.exit(0)

    elif cmd == 'pingall':
        if logVerbosity > 2:
            print "Pinging all peers, waiting %s seconds for results" % timeout['ping']
        res = client.pingall()
        print res
        sys.exit(0)

#@-node:main
#@-others
#@-node:funcs
#@+node:MAINLINE
#@+others
#@+node:mainline
if __name__ == '__main__':

    main()

#@-node:mainline
#@-others
#@-node:MAINLINE
#@-others

#@-node:@file stasher.py
#@-leo
