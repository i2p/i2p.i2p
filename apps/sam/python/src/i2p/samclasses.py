
# -------------------------------------------------------------
# samclasses.py: Lower-level SAM classes, for internal use.
# -------------------------------------------------------------

"""
Lower-level SAM API, interfaces with SAM Bridge.

For internal use only.

Use the higher level i2p.socket module for your own programs.

For details on SAM, see "Simple Anonymous Messaging (SAM) v1.0,"
as published by jrandom.

Class Overview:

 - SAMTerminal:     Message sender/reader, talks to SAM Bridge.
 - StringBuffer:    Queue for character data.
 - BaseSession:     SAM session classes are derived from this.
 - StreamSession:   SAM stream session class, threadsafe, high level.
 - DatagramSession: SAM datagram session, threadsafe, high level.
 - RawSession:      SAM raw session, threadsafe, high level.

Note that a 'None' timeout is an infinite timeout: it
blocks forever if necessary.

Todo:
  - Error handling is a huge mess.  Neaten it up.
    Subclass a ErrorMixin class, then use set_error(e),
    check_error(), get_error().
  - Streams are a huge mess.  Neaten them up.
  - This whole interface is a tad confusing.  Neaten it up.
"""

# ---------------------------------------------------------
# Imports
# ---------------------------------------------------------

import Queue, traceback, random, sys, shlex
import thread, threading, time, string

# ---------------------------------------------------------
# Import i2p and i2p.socket (for defaults and errors)
# ---------------------------------------------------------

import i2p
import i2p.socket
from i2p.pylib import socket as pysocket   # Import Python socket

# ---------------------------------------------------------
# Functions
# ---------------------------------------------------------

def sleep(): time.sleep(0.01)   # Sleep between thread polls
def timer(): return time.time() # High resolution timer
                                # Do NOT use time.clock() as it
                                # drops sleep() time on Linux.

log = False                     # Logging flag.  Logs to ./log.txt.

# -----------------------------------------------------
# SAMTerminal
# -----------------------------------------------------

class SAMTerminal:
  """Message-by-message communication with SAM through a single
     pysocket.  _on_* messages are dispatched to msgobj."""

  def __init__(self, addr, msgobj):
    try: self.host, self.port = addr.split(':')
    except: raise ValueError('sam port required')
    self.port = int(self.port)
    self.sock=pysocket.socket(pysocket.AF_INET,pysocket.SOCK_STREAM)
    self.msgobj = msgobj
    try:
      self.sock.connect((self.host, self.port))
    except:
      raise i2p.RouterError('could not contact SAM bridge on ' +
                            self.host + ':' + str(self.port))
    thread.start_new_thread(self._poll_loop, ())
    self.error = None
    self.lost_error = i2p.RouterError('SAM bridge connection lost')
  
  def _poll_loop(self):
    """Polling loop for incoming messages."""
    try:
      while True:
        # Read until newline
        line = []
        while True:
          try: c = self.sock.recv(1)
          except pysocket.error, ex: self.error = self.lost_error
          if c == '': self.error = self.lost_error
          if self.error != None: return
          if c == '\n': break
          if c != '': line += [c]
        line = ''.join(line)
        if log:
          logf = open('log.txt', 'a')
          logf.write('\n' + line + '\n')
          logf.close()
        (msg, kwargs) = self._samdecode(line)
        # Read N bytes if SIZE=N is present.
        if 'SIZE' in kwargs:
          data = []
          remain = int(kwargs['SIZE'])
          while True:
            try: s = self.sock.recv(remain)
            except pysocket.error, ex: self.error = self.lost_error
            if s == '': self.error = self.lost_error
            if self.error != None: return
            if s != '': data += [s]
            remain -= len(s)
            if remain <= 0: break
          data = ''.join(data)
          # Store the read data in kwargs['DATA'].
          kwargs['DATA'] = data
          del kwargs['SIZE']
        # Dispatch the message
        try: self.on_message(msg, kwargs)
        except Exception, e:
          # On exception in on_message, print a warning, keep going.
          print 'Unhandled exception in polling thread.'
          traceback.print_exc()

        # Don't need to sleep since recv() blocks.
      # End of while loop
    except Exception, e:
      # For other exceptions, print a fatal error and stop polling.
      print 'Fatal exception in polling thread'
      traceback.print_exc(); sys.exit()

  def _samdecode(self, s):
    """Given a SAM command, returns (a, b), where a is the string at
       the beginning of the command, and b is a dictionary of name,
       value pairs for the command."""
    (args, kwargs) = ([], {})
    for w in shlex.split(s):
      if '=' in w: kwargs[w.split('=')[0]] = w.split('=')[1]
      else: args += [w]
    return (' '.join(args), kwargs)

  def check_message(self, kwargs):
    """Raises an error if kwargs['RESULT'] != 'OK'."""
    if not kwargs.get('RESULT', '') in ['OK', '']:
      raise i2p.socket.NetworkError((kwargs['RESULT'],
                                  kwargs.get('MESSAGE', '')))

  def on_message(self, msg, kwargs):
    """Process a SAM message that was received.  Dispatch to
       self._on_MESSAGE_NAME(**kwargs)."""
    name = '_on_' + msg.upper().replace(' ', '_')
    getattr(self.msgobj, name)(**kwargs)

  def send_message(self, msg):
    """Send a message to the SAM bridge.  A newline will be
       automatically added if none is present."""
    self.check()
    if not '\n' in msg: msg = msg + '\n'
    if log:
      logf = open('log.txt', 'a')
      logf.write('\n' + msg)
      logf.close()
    try: self.sock.sendall(msg)
    except pysocket.error: self.error = self.lost_error
    self.check()

  def check(self):
    """Raise an error if terminal was closed, otherwise do
       nothing."""
    if self.error != None: raise self.error

  def close(self):
    """Close the SAM terminal."""
    # If data is sent via STREAM SEND, and the socket is closed
    # immediately, the data will be lost.  Delay 0.01 s to fix this
    # bug (tested Windows, Linux).
    time.sleep(0.01)
    self.error = i2p.socket.ClosedError()
    self.sock.close()

  def queue_get(self, q):
    """Identical to q.get() unless a call to self.check() fails,
       in which case the waiting is cut short with an error."""
    while True:
      try: return q.get_nowait()
      except Queue.Empty: pass
      self.check()
      sleep()


# -------------------------------------------------------
# StringBuffer: A FIFO for string data.
# -------------------------------------------------------

class Deque: 
  """A double-ended queue."""
  def __init__(self):
    self.a = []
    self.b = []
  def push_last(self, obj):
    """Append obj to the end of the deque."""
    self.b.append(obj)
  def push_first(self, obj):
    """Prepend obj to the beginning of the deque."""
    self.a.append(obj)
  def _partition(self):
    if len(self) > 1:
      self.a.reverse()
      all = self.a + self.b
      n = len(all) / 2
      self.a = all[:n]
      self.b  = all[n:]
      self.a.reverse()
  def pop_last(self):
    """Pop an item off the end of the deque, and return it."""
    if not self.b: self._partition()
    try: return self.b.pop()
    except: return self.a.pop()
  def pop_first(self):
    """Pop an item off the beginning of the deque, and return it."""
    if not self.a: self._partition()
    try: return self.a.pop()
    except: return self.b.pop()
  def __len__(self):
    """Number of items in the deque."""
    return len(self.b) + len(self.a)

class StringBuffer(Deque):
  """A FIFO for characters.  Strings can be efficiently
     appended to the end, and read from the beginning.

     Example:
       >>> B = StringBuffer('Hello W')
       >>> B.append('orld!')
       >>> B.read(5)
       'Hello'
       >>> B.read()
       'World!'
  """
  def __init__(self, s=''):
    Deque.__init__(self)
    self.length = 0
    self.append(s)
  def append(self, s):
    """Append string data to the end of the buffer."""
    n = 128
    for block in [s[i:i+n] for i in range(0,len(s),n)]:
      self.push_last(block)
    self.length += len(s)
  def prepend(self, s):
    """Prepend string data to the beginning of the buffer."""
    n = 128
    blocks = [s[i:i+n] for i in range(0,len(s),n)]
    blocks.reverse()
    for block in blocks:
      self.push_first(block)
    self.length += len(s)
  def read(self, n=None):
    """Read n bytes of data (or less if less data available) from the
       beginning of the buffer.  The data is removed.  If n is
       omitted, read the entire buffer."""
    if n == None or n > len(self): n = len(self)
    destlen = len(self) - n
    ans = []
    while len(self) > destlen:
      ans += [self.pop_first()]
      self.length -= len(ans[-1])
    ans = ''.join(ans)
    self.prepend(ans[n:])
    ans = ans[:n]
    return ans
  def peek(self, n=None):
    """Like read(), but do not remove the data that is returned."""
    ans = self.read(n)
    self.prepend(ans)
    return ans
  def __len__(self): return self.length
  def __str__(self): return self.peek()
  def __repr__(self): return 'StringBuffer(' + str(self) + ')'

# -----------------------------------------------------
# BaseSession
# -----------------------------------------------------

class BaseSession:
  """Base session, from which StreamSession, DatagramSession,
     and RawSession are derived."""

  def __init__(self, addr=''):
    if addr == '': addr = i2p.socket.samaddr
    self.term = SAMTerminal(addr=addr, msgobj=self)
    self.lock = threading.Lock()  # Data lock.
    self.closed = False
    self.qhello = Queue.Queue()   # Thread messaging, HELLO REPLY.
    self.qnaming = Queue.Queue()  # Thread messaging, NAMING REPLY.
    self.qsession = Queue.Queue() # Thread messaging, SESSION STATUS.
    self._hello()                 # Do handshake with SAM bridge.

  def _hello(self):
    """Internal command, handshake with SAM terminal."""
    self.term.send_message('HELLO VERSION MIN=' +
         str(i2p.socket.samver) + ' MAX=' + str(i2p.socket.samver))
    self.term.check_message(self.term.queue_get(self.qhello))

  def _on_HELLO_REPLY(self, **kwargs):
    """Internal command, got HELLO REPLY."""
    self.qhello.put(kwargs)       # Pass kwargs back to _hello.

  def _on_SESSION_STATUS(self, **kwargs):
    """Internal command, got SESSION STATUS."""
    self.qsession.put(kwargs)     # Pass kwargs back to main thread.

  def _namelookup(self, name):
    """Internal command, does a NAMING LOOKUP query."""
    self.term.send_message('NAMING LOOKUP NAME=' + name)
    # Read back response, check it, and return X in VALUE=X.
    kwargs = self.term.queue_get(self.qnaming)
    self.term.check_message(kwargs)
    return kwargs['VALUE']

  def _on_NAMING_REPLY(self, **kwargs):
    """Internal command, got NAMING REPLY."""
    self.qnaming.put(kwargs)      # Pass kwargs back to _namelookup.

  def _set_properties(self):
    """Internal command, call at end of __init__ to set up
       properties."""
    self.dest = self._namelookup('ME')

  def close(self):
    """Close the session."""
    # Synchronize
    self.lock.acquire()
    try:
      # Close the terminal if we're not already closed.
      if not self.closed: self.term.close()
      self.closed = True
    finally: self.lock.release()

  def _encode_kwargs(self, **kwargs):
    """Internal command, encode extra kwargs for passing to
       SESSION CREATE."""
    ans = ''
    for k in kwargs:
      if k == 'in_depth':
        ans += ' tunnels.depthInbound=' +      \
               str(int(kwargs['in_depth']))
      elif k == 'out_depth':
        ans += ' tunnels.depthOutbound=' +     \
               str(int(kwargs['out_depth']))
      else:
        raise ValueError('unexpected keyword argument ' + repr(k))
    return ans

# -----------------------------------------------------
# StreamSession
# -----------------------------------------------------

class StreamSession(BaseSession):
  """Stream session.  All methods are blocking and threadsafe."""

  def __init__(self, name, addr='', **kwargs):
    if addr == '': addr = i2p.socket.samaddr
    BaseSession.__init__(self, addr)
    self.idmap = {}                # Maps id to Stream object.
    self.qaccept = Queue.Queue()   # Thread messaging, accept.
    self.name = name
    self.max_accept = 0            # Max queued incoming connections.

    # Create stream session.
    if name == '':
      name = 'TRANSIENT'

    # DIRECTION=BOTH (the default) is used because we can't know in
    # advance whether a session will call listen().

    self.term.send_message('SESSION CREATE STYLE=STREAM' +
              ' DESTINATION=' + name + self._encode_kwargs(**kwargs))
    self.term.check_message(self.term.queue_get(self.qsession))

    self._set_properties()

  def connect(self, dest, timeout=None):
    """Create a stream connected to remote destination 'dest'.  The
       id is random.  If the timeout is exceeded, do NOT raise an
       error; rather, return a Stream object with .didconnect set
       to False."""
    if not isinstance(dest, type('')): raise TypeError
    # Synchronize
    self.lock.acquire()
    try:
      # Pick a positive stream id at random.
      while True:
        # 9/10 probability of success per iteration
        id = random.randrange(1, len(self.idmap) * 10 + 2)
        if not id in self.idmap:
          ans = Stream(self, dest, id, didconnect=False)
          self.idmap[id] = ans
          break
    finally: self.lock.release()
    # Send STREAM CONNECT and wait for reply.
    self.term.send_message('STREAM CONNECT ID=' + str(id) +
                           ' DESTINATION=' + str(dest))

    # Now wait until the stream's .didconnect flag is set to True.
    if timeout != None: end = timer() + timeout
    while True:
      self.term.check()
      if ans.didconnect: break
      if timeout != None and timer() >= end: break
      sleep()

    return ans

  def _on_STREAM_STATUS(self, **kwargs):
    """Internal command, got STREAM STATUS.  Unblocks connect."""
    # Store error is needed
    try: self.term.check_message(kwargs)
    except Exception, e:
      try: self.idmap[int(kwargs['ID'])].err = e
      except: pass # Closed too quickly

    # Now set .didconnect flag to True.
    try: self.idmap[int(kwargs['ID'])].didconnect = True
    except: pass   # Closed too quickly

  def accept(self, timeout=None):
    """Wait for incoming connection, and return a Stream object
       for it."""
    if self.max_accept <= 0:
      raise i2p.Error('listen(n) must be called before accept ' + 
                      '(n>=1)')
    if timeout != None: end = timer() + timeout
    while True:
      self.term.check()
      # Synchronized
      self.lock.acquire()
      try:
        # Get Stream object if available.
        if self.qaccept.qsize() > 0:
          return self.term.queue_get(self.qaccept)
      finally: self.lock.release()
      if timeout != None and timer() >= end: break
      sleep()

    # Handle timeout and blocking errors
    if timeout == 0.0:
      raise i2p.socket.BlockError('command would have blocked')
    else:
      raise i2p.socket.Timeout('timed out')

  def listen(self, backlog):
    """Set maximum number of queued connections."""
    if self.closed: raise sam.ClosedError()
    self.max_accept = backlog

  def _on_STREAM_CONNECTED(self, **kwargs):
    """Got STREAM CONNECTED command.  This is what accept() commands
       wait for."""
    # Synchronize
    self.lock.acquire()
    try:
      # Drop connection if over maximum size.
      if self.qaccept.qsize() >= self.max_accept:
        self.term.send_message('STREAM CLOSE ID=' +
                               str(int(kwargs['ID'])))
        return

      # Parse, create Stream, and place on self.qaccept.
      self.term.check_message(kwargs)
      # A negative id is chosen for us
      id = int(kwargs['ID'])
      self.idmap[id] = Stream(self, kwargs['DESTINATION'], id)
      # Pass Stream object back to accept.
      self.qaccept.put(self.idmap[id])
    finally: self.lock.release()

  def _send_stream(self, id, data):
    """Internal command, send data to stream id.  Use Stream.send
       in your code."""
    self.term.send_message('STREAM SEND ID=' + str(id) + ' SIZE=' +
                           str(len(data)) + '\n' + data)

  def _on_STREAM_CLOSED(self, **kwargs):
    """Got STREAM CLOSED command.  Call idmap[id].on_close(e) and
       delete idmap[id]."""
    id = int(kwargs['ID'])

    # No error is produced for a graceful remote close.
    e = None
    try: self.term.check_message(kwargs)
    except i2p.Error, err: e = err

    # Synchronize
    self.lock.acquire()
    try:
      # Sent STREAM CLOSE, SAM didn't hear us in time.
      if not id in self.idmap: return
      # Pop id from self.idmap, if available.
      obj = self.idmap[id]
      del self.idmap[id]
    finally: self.lock.release()

    # Process on_close message.
    obj.on_close(None)

  def _on_STREAM_RECEIVED(self, **kwargs):
    """Got STREAM RECEIVED command.  Dispatch to
       idmap[id].on_receive(s)."""
    id = int(kwargs['ID'])
    if not id in self.idmap:
      # _on_STREAM_CONNECTED blocks until self.idmap[id] is properly
      # set up.  Therefore, we have received a stream packet despite
      # closing the stream immediately after _on_STREAM_CONNECTED
      # (SAM ignored us).  So ignore it.
      return
    self.idmap[id].on_receive(kwargs['DATA'])

  def __len__(self):
    """Unconnected session; has no read data available."""
    return 0


class Stream:
  """Receives and sends data for an individual stream."""

  def __init__(self, parent, remotedest, id, didconnect=True):
    self.parent = parent
    self.buf = StringBuffer()
    self.localdest = parent.dest
    self.remotedest = remotedest
    self.id = id
    # Data lock.  Allow multiple acquire()s by same thread
    self.lock = threading.RLock()
    self.closed = False
    # Error message, on STREAM STATUS, or on STREAM CLOSED.
    self.err = None
    # Whether stream got a STREAM CONNECTED message
    self.didconnect = didconnect

  def send(self, s):
    """Sends the string s, blocking if necessary."""
    id = self.id
    if self.closed or id == None:
      if self.err != None: raise self.err
      raise i2p.socket.ClosedError('stream closed')
    if len(s) == 0: return
    nmax = 32768
    for block in [s[i:i+nmax] for i in range(0,len(s),nmax)]:
      self.parent._send_stream(id, block)

  def recv(self, n, timeout=None, peek=False, waitall=False):
    """Reads up to n bytes in a manner identical to socket.recv.
       Blocks for up to timeout seconds if n > 0 and no data is
       available (timeout=None means wait forever).  If still no data
       is available, raises BlockError or Timeout.  For a closed
       stream, recv will read the data stored in the buffer until
       EOF, at which point the read data will be truncated.  If peek
       is True, the data is not removed.  If waitall is True, reads
       exactly n bytes, or raises BlockError or Timeout as
       appropriate.  Returns data."""

    if n < 0: raise ValueError
    if n == 0: return ''

    minlen = 1
    if waitall: minlen = n

    if timeout != None: end = timer() + timeout
    while True:
      # Synchronized check and read until data available.
      self.parent.term.check()
      self.lock.acquire()
      try:
        if len(self.buf) >= minlen:
          if peek: return self.buf.peek(n)
          else: return self.buf.read(n)
        # Graceful close: return as much data as possible
        # (up to n bytes).
        if self.closed and self.err == None: return self.buf.read(n)
        # Ungraceful close: raise an error.
        if self.err != None: raise self.err
      finally: self.lock.release()
      if timeout != None and timer() >= end: break
      sleep()

    # Handle timeout and blocking error
    if timeout == 0.0:
      raise i2p.socket.BlockError('command would have blocked')
    else:
      raise i2p.socket.Timeout('timed out')

  def __len__(self):
    """Current length of read buffer."""
    return len(self.buf)

  def close(self):
    """Close the stream.  Threadsafe."""
    # Synchronize self.parent.
    self.parent.lock.acquire()
    try:
      if not self.closed:
        self.closed = True
        id = self.id
        # Set self.id to None, so we don't close a new stream by
        # accident.
        self.id = None
        if not id in self.parent.idmap: return
        self.parent.term.send_message('STREAM CLOSE ID=' + str(id))
        # No error is produced for a locally closed stream
        self.on_close(None)
        del self.parent.idmap[id]
    finally: self.parent.lock.release()

  def on_receive(self, s):
    # Synchronize
    self.lock.acquire()
    try:
      self.buf.append(s)
    finally: self.lock.release()

  def on_close(self, e):
    self.closed = True
    self.err = e

  def __del__(self):
    self.close()

# -----------------------------------------------------
# DatagramSession
# -----------------------------------------------------

class DatagramSession(BaseSession):
  """Datagram session.  All methods are blocking and threadsafe."""

  def __init__(self, name, addr='', **kwargs):
    if addr == '': addr = i2p.socket.samaddr
    BaseSession.__init__(self, addr)
    self.buf = Deque()                    # FIFO of incoming packets.
    self.name = name

    # Create datagram session
    if name == '': name = 'TRANSIENT'
    self.term.send_message('SESSION CREATE STYLE=DATAGRAM ' +
               'DESTINATION=' + name + self._encode_kwargs(**kwargs))
    self.term.check_message(self.term.queue_get(self.qsession))

    self._set_properties()

  def _on_DATAGRAM_RECEIVED(self, **kwargs):
    """Internal method, got DATAGRAM RECEIVED."""
    # Synchronized
    self.lock.acquire()
    try:
      self.buf.push_last((kwargs['DATA'], kwargs['DESTINATION']))
    finally: self.lock.release()

  def send(self, s, dest):
    """Send packet with contents s to given destination."""
    # Raise error if packet is too large.
    if len(s) > i2p.socket.MAX_DGRAM:
      raise ValueError('packets must have length <= ' +
                       str(i2p.socket.MAX_DGRAM) + ' bytes')
    self.term.send_message('DATAGRAM SEND DESTINATION=' + dest +
                           ' SIZE=' + str(len(s)) + '\n' + s)

  def recv(self, timeout=None, peek=False):
    """Get a single packet.  Blocks for up to timeout seconds if
       n > 0 and no packet is available (timeout=None means wait
       forever).  If still no packet is available, raises BlockError
       or Timeout.  Returns the pair (data, address).  If peek is
       True, the data is not removed."""
    if timeout != None: end = timer() + timeout
    while True:
      self.term.check()
      # Synchronized check and read until data available.
      self.lock.acquire()
      try:
        if len(self.buf) > 0:
          if peek:
            ans = self.buf.pop_first()
            self.buf.push_first(ans)
            return ans
          else:
            return self.buf.pop_first()
      finally: self.lock.release()
      if timeout != None and timer() >= end: break
      sleep()

    # Handle timeout and blocking error
    if timeout == 0.0:
      raise i2p.socket.BlockError('command would have blocked')
    else:
      raise i2p.socket.Timeout('timed out')

  def __len__(self):
    """Number of packets in read buffer."""
    return len(self.buf)

# -----------------------------------------------------
# RawSession
# -----------------------------------------------------

class RawSession(BaseSession):
  """Raw session.  All methods are blocking and threadsafe."""

  def __init__(self, name, addr='', **kwargs):
    if addr == '': addr = i2p.socket.samaddr
    BaseSession.__init__(self, addr)
    self.buf = Deque()                    # FIFO of incoming packets.
    self.name = name

    # Create raw session
    if name == '': name = 'TRANSIENT'
    self.term.send_message('SESSION CREATE STYLE=RAW DESTINATION=' +
                           name + self._encode_kwargs(**kwargs))
    self.term.check_message(self.term.queue_get(self.qsession))

    self._set_properties()

  def _on_RAW_RECEIVED(self, **kwargs):
    """Internal method, got RAW RECEIVED."""
    # Synchronized
    self.lock.acquire()
    try:
      self.buf.push_last((kwargs['DATA'], ''))
    finally: self.lock.release()

  def send(self, s, dest):
    """Send packet with contents s to given destination."""
    # Raise error if packet is too big
    if len(s) > i2p.socket.MAX_RAW:
      raise ValueError('packets must have length <= ' +
                       str(i2p.socket.MAX_RAW) + ' bytes')
    self.term.send_message('RAW SEND DESTINATION=' + dest +
                           ' SIZE=' + str(len(s)) + '\n' + s)

  def recv(self, timeout=None, peek=False):
    """Identical to DatagramSocket.recv.  The from address is an
       empty string."""
    if timeout != None: end = timer() + timeout
    while True:
      self.term.check()
      # Synchronized check and read until data available.
      self.lock.acquire()
      try:
        if len(self.buf) > 0:
          if peek:
            ans = self.buf.pop_first()
            self.buf.push_first(ans)
            return ans
          else:
            return self.buf.pop_first()
      finally: self.lock.release()
      if timeout != None and timer() >= end: break
      sleep()

    # Handle timeout and blocking error
    if timeout == 0.0:
      raise i2p.socket.BlockError('command would have blocked')
    else:
      raise i2p.socket.Timeout('timed out')

  def __len__(self):
    """Number of packets in read buffer."""
    return len(self.buf)


# -----------------------------------------------------
# End of file
# -----------------------------------------------------
