
# -------------------------------------------------------------
# sam.py: I2P Project -- SAM Python API
# -------------------------------------------------------------

"""
SAM Python API
"""

import i2p

import samclasses, threading, time, copy, Queue, thread
import socket as pysocket
import select as pyselect

# --------------------------------------------------
# Global variables
# --------------------------------------------------

# Ports
samaddr   = '127.0.0.1:7656'    # Default port for SAM Bridge

# Flags for recv, recvfrom.
MSG_PEEK     = 2                # Peek at incoming message
MSG_WAITALL  = 64               # Wait for data or error
MSG_DONTWAIT = 128              # Nonblocking

# Packet sizes
MAX_DGRAM     = 31744           # Max size of datagram packet
MAX_RAW       = 32768           # Max size of raw packet

# Socket types
SOCK_STREAM   = 1               # Stream socket
SOCK_DGRAM    = 2               # Datagram socket
SOCK_RAW      = 3               # Raw socket

# Miscellaneous
samver        = 1.0             # SAM version implemented

# --------------------------------------------------
# Errors
# --------------------------------------------------

class Error(i2p.Error):
  """Base class for all SAM errors."""

class NetworkError(Error):
  """Network error occurred within I2P.
     The error object is a 2-tuple: (errtag, errdesc).
     errtag is a SAM error string,
     errdesc is a human readable error description.
  """

class ClosedError(Error):
  """A command was used on a socket that closed gracefully."""

class BlockError(Error):
  """Socket call would have blocked."""

class Timeout(Error):
  """Time out occurred for a socket which had timeouts enabled
     via a prior call to settimeout()."""

# --------------------------------------------------
# Sockets
# --------------------------------------------------

# Note: socket(), __make_session() and Socket() should have same args
def socket(session, type, samaddr=samaddr, **kwargs):
  r"""Create a new socket.  Argument session should be a session
     name -- if the name has not yet been used, an I2P
     Destination will be created for it, otherwise, the
     existing Destination will be re-used.  An empty session
     string causes a transient session to be created.  Argument
     type is one of SOCK_STREAM, SOCK_DGRAM, or SOCK_RAW.

     I2P configuration keyword arguments:

       * in_depth  - depth of incoming tunnel (default 2)
       * out_depth - depth of outgoing tunnel (default 2)

     A single session may be shared by more than one socket, if
     the sockets are the same type, and if the sockets are
     created within the same Python process.  The socket
     objects are multithread-safe.

     Examples:
       a = i2p.socket('Alice', i2p.SOCK_STREAM)
       b = i2p.socket('Bob',   i2p.SOCK_DGRAM,
                      in_depth=2, out_depth=5)

     The created object behaves identically to a socket from
     module socket, with the following exceptions:

       * I2P Destinations are used as address arguments [1].
       * bind is a no-op: sockets are always bound.
       * send* methods send all data and are non-blocking.

     A given session name can only be open in a single Python
     program at a time.  If you need to overcome this
     limitation, consider patching I2P.

     [1]. Alternatively, a host name can be used as an address.
          It will be resolved using hosts.txt.

     For details on how to use socket objects, see
     http://www.python.org/doc/current/lib/socket-objects.html

     See the examples directory for code examples.
  """

  return Socket(session, type, samaddr, **kwargs)


# --------------------------------------------------
# Socket session objects
# --------------------------------------------------

# Global list of session objects.
_sessions = {}
_session_lock = threading.Lock()

def _make_session(session, type, samaddr, **kwargs):
  """Make a session object (eg samclasses.StreamSession).  Same
     arguments as socket().  Return an existing session object
     if one has been previously created under the given name.
  """
  # Synchronize
  _session_lock.acquire()
  try:
    if   type == SOCK_STREAM: C = samclasses.StreamSession
    elif type == SOCK_DGRAM:  C = samclasses.DatagramSession
    elif type == SOCK_RAW:    C = samclasses.RawSession
    else: raise ValueError('bad socket type')
    # Get existing session, if available
    if session != '' and _sessions.has_key(session):
      if _sessions[session].__class__ != C:
        raise ValueError('session ' + repr(session) + ' was ' +
                         'created with a different session type.')
      return _sessions[session]
    # Create new session
    if   type == SOCK_STREAM: ans = C(session, samaddr, **kwargs)
    elif type == SOCK_DGRAM:  ans = C(session, samaddr, **kwargs)
    elif type == SOCK_RAW:    ans = C(session, samaddr, **kwargs)
    if session != '': _sessions[session] = ans
    return ans
  finally: _session_lock.release()

def _wrap_stream(stream, parent_socket):
  """Wraps a Socket object around a samclasses.Stream object."""
  s = Socket('', 0, dummy_socket=True)
  s.sessobj    = stream
  s.remotedest = stream.remotedest
  s.dest       = parent_socket.dest
  s.session    = parent_socket.session
  s.type       = parent_socket.type
  s.timeout    = None
  s.samaddr    = parent_socket.samaddr
  s.closed     = False
  return s

# --------------------------------------------------
# Socket class
# --------------------------------------------------

class Socket:
  """A socket object."""

  # Docstrings for pydoc.  These variables will be overwritten.
  dest    = property(doc='Local I2P Destination of socket')
  session = property(doc='Session name')
  type    = property(doc='Socket type: SOCK_STREAM, SOCK_DGRAM,' +
                         ' or SOCK_RAW.')
  # FIXME: Use getsockopt to detect errors.

  def __init__(self, session, type, samaddr=samaddr, **kwargs):
    """Equivalent to socket()."""
    if kwargs.has_key('dummy_socket'): return
    self.sessobj  = _make_session(session, type, samaddr, **kwargs)
    self.dest     = self.sessobj.dest
    self.session  = session
    self.type     = type
    self.timeout  = None        # None indicates blocking mode
    self.samaddr  = samaddr
    self.closed   = False       # Was current object closed?
    self.lock     = threading.Lock()

  def _verify_open(self):
    """Verify that the socket has not been closed."""
    if self.closed == True:
      raise ClosedError('socket closed')

  def _verify_stream(self):
    """Raise an error if socket is not a SOCK_STREAM."""
    if self.type != SOCK_STREAM:
      raise i2p.Error('operation not supported')
    # FIXME: Check for errors also.

  def _verify_connected(self, needs_to_be_connected=True):
    """Raise an error if socket is not a connected stream socket."""
    self._verify_stream()
    if not hasattr(self.sessobj, 'remotedest'):
      raise i2p.Error('socket is not connected')
    if needs_to_be_connected and not self.sessobj.didconnect:
      raise i2p.Error('socket is in the process of connecting')
    # FIXME: Check for errors also.

  def _verify_not_connected(self):
    """Verify that the socket is not currently connected, and is not
       in the process of connecting."""
    self._verify_stream()
    if hasattr(self.sessobj, 'remotedest'):
      raise i2p.Error('socket is already connected')
    # FIXME: Check for errors also.

  def accept(self):
    self._verify_open()
    self._verify_not_connected()
    # Raises BlockError or Timeout if not ready.
    C = _wrap_stream(self.sessobj.accept(self.timeout), self)
    return (C, C.remotedest)

  def bind(self, address):
    self._verify_open()
    self._verify_not_connected()

  def close(self):
    try:
      self._verify_connected()
      connected = True
    except i2p.Error:
      connected = False
    if connected:
      # Close the Stream object.
      self.sessobj.close()
    else:
      # Never close a session object.
      pass
    self.closed = True

  def connect(self, address):
    # Synchronized.  Lock prevents two connects from occurring at the
    # same time in different threads.
    self.lock.acquire()
    try:
      self._verify_open()
      if self.type == SOCK_DGRAM or self.type == SOCK_RAW:
        self.packet_dest = address
        return

      self._verify_not_connected()
      address = resolve(address, self.samaddr)

      timeout = self.timeout
      unwrap = self.sessobj.connect(address, timeout=timeout)
      w = _wrap_stream(unwrap, self)
      self.sessobj    = w.sessobj
      self.remotedest = w.remotedest

      if self.sessobj.err != None:
        raise self.sessobj.err

      # Raise error if not yet connected
      if not self.sessobj.didconnect:
        if timeout == 0.0:
          raise BlockError('command would have blocked.  use ' +
                        'select() to find when socket is connected')
        else: raise Timeout('timed out.  use select() to find ' +
                            'when socket is connected')

    finally: self.lock.release()

  def connect_ex(self, address):
    try: self.connect(address)
    except i2p.Error, e: return e

  def getpeername(self):
    self._verify_connected()
    return self.remotedest

  def getsockname(self):
    return self.dest

  def listen(self, backlog):
    self._verify_open()
    self._verify_not_connected()
    self.sessobj.listen(backlog)

  def makefile(self, mode='r', bufsize=-1):
    self._verify_open()
    self._verify_connected()
    return pysocket._fileobject(self, mode, bufsize)

  def recv(self, bufsize, flags=0):
    # FIXME: What about recv'ing if connected in asynchronous mode?
    # It is acceptable to call recv() after a stream has closed
    # gracefully.  It is an error to call recv() after a stream has
    # closed due to an I2P network error.
    timeout = self.timeout
    (peek, waitall, dontwait) = \
      (flags & MSG_PEEK, flags & MSG_WAITALL, flags & MSG_DONTWAIT)
    if dontwait: timeout = 0.0

    if self.type == SOCK_STREAM:
      self._verify_connected()
      return self.sessobj.recv(bufsize, timeout, peek, waitall)
    else:
      return self.recvfrom(bufsize, flags)[0]

  def recvfrom(self, bufsize, flags=0):
    """For a datagram or raw socket, bufsize = -1 indicates that the
       entire packet should be retrieved."""
    timeout = self.timeout
    (peek, waitall, dontwait) = \
      (flags & MSG_PEEK, flags & MSG_WAITALL, flags & MSG_DONTWAIT)
    if dontwait: timeout = 0.0

    if self.type == SOCK_STREAM:
      self._verify_connected()
      if bufsize < 0: raise ValueError('bufsize must be >= 0')
      return (self.sessobj.recv(bufsize, timeout, peek, waitall), \
              self.remotedest)
    else:
      return self.sessobj.recv(timeout, peek)[:bufsize]

  def send(self, string, flags=0):
    self._verify_open()
    if self.type == SOCK_DGRAM or self.type == SOCK_RAW:
      if not hasattr(self, 'packet_dest'):
        raise i2p.Error('use connect or sendto to specify a ' +
                        'Destination')
      self.sendto(string, flags, self.packet_dest)
      return

    self._verify_connected()
    if self.closed:
      raise i2p.Error('send operation on closed socket')
    # FIXME: What about send'ing if connected in asynchronous mode?
    self.sessobj.send(string)

  def sendall(self, string, flags=0):
    self.send(string)

  def sendto(self, string, flags, address):
    self._verify_open()
    if not self.type in [SOCK_DGRAM, SOCK_RAW]:
      raise i2p.Error('operation not supported')
    if self.closed:
      raise i2p.Error('sendto operation on closed socket')
    address = resolve(address, self.samaddr)
    self.sessobj.send(string, address)

  def setblocking(self, flag):
    if flag: self.timeout = None
    else: self.timeout = 0.0

  def settimeout(self, value):
    self.timeout = value

  def gettimeout(self):
    return self.timeout

  def __deepcopy__(self, memo):
    return copy.copy(self)

# --------------------------------------------------
# Poll and select
# --------------------------------------------------

POLLIN   = 1                    # There is data to read     
POLLPRI  = 1                    # Same as POLLIN
POLLOUT  = 4                    # Ready for output
POLLERR  = 8                    # Wait for error condition
POLLHUP  = 16                   # Not implemented
POLLNVAL = 32                   # Not implemented

class Poll:
  """Class implementing poll interface.  Works for Python sockets
     and SAM sockets."""
  def __init__(self):
    self.fds = {}               # Maps _hash() -> (socket, mask)
  def _hash(self, fd):
    if isinstance(fd, int):
      return fd                 # Use the fd itself if integer.
    else:
      return id(fd)             # Use object address (no copies!)
  def register(self, fd, eventmask=POLLIN|POLLOUT|POLLERR):
    self.fds[self._hash(fd)] = (fd, eventmask)
  def unregister(self, fd):
    del self.fds[self._hash(fd)]
  def poll(self, timeout=None):
    readlist, writelist, errlist = [], [], []
    for F, mask in self.fds:
      if mask & POLLIN:  readlist  += [F]
      if mask & POLLOUT: writelist += [F]
      if mask & POLLERR: errlist   += [F]
    (Rs, Ws, Es) = select(readlist, writelist, errlist,
                          timeout=timeout)
    ans = []
    for R in Rs: ans.append((R, POLLIN))
    for W in Ws: ans.append((W, POLLOUT))
    for E in Es: ans.append((E, POLLERR))
    return ans

def poll():
  """Returns a polling object.  Works on SAM sockets and Python
     sockets.  See select.poll() in the Python library for more
     information."""
  return Poll()

def select(readlist, writelist, errlist, timeout=None):
  """Performs a select call.  Works on SAM sockets and Python
     sockets.  See select.select() in the Python library for more
     information."""
  Rans = []
  Wans = []
  Eans = []
  if timeout != None: end = time.clock() + timeout
  while True:
    # FIXME: Check performance.
    # Use pysocket.poll for Python sockets, if needed for speed.

    # Check for read availability.
    for R in readlist:
      if isinstance(R, int) or hasattr(R, 'fileno'):
        # Python socket
        if len(pyselect.select([R], [], [], 0.0)[0]) > 0:
          Rans.append(R)
      else:
        # SAM Socket
        if R.type == SOCK_STREAM:
          try:
            R._verify_connected()
            Rans.append(R)
          except:
            pass
        else:
          if len(R) > 0: Rans.append(R)

    # Check for write availability.
    for W in writelist:
      if isinstance(W, int) or hasattr(W, 'fileno'):
        # Python socket
        if len(pyselect.select([], [W], [], 0.0)[1]) > 0:
          Wans.append(W)
      else:
        # SAM Socket
        if W.type == SOCK_STREAM:
          try:
            W._verify_connected()
            Wans.append(W)
          except:
            pass
        else:
          Wans.append(W)

    # Check for error conditions.
    # These can only be stream errors.
    for E in errlist:
      if isinstance(E, int) or hasattr(E, 'fileno'):
        # Python socket
        if len(pyselect.select([], [], [E], 0.0)[2]) > 0:
          Eans.append(E)
      else:
        if E.type == SOCK_STREAM:
          try:
            # FIXME: Use a ._get_error() function for errors.
            # Socket can only have an error if it connected.
            E._verify_connected()
            if E.sessobj.err != None:
              Eans.append(E)
          except:
            pass
    if timeout != None and time.clock() >= end: break
    if len(Rans) != 0 or len(Wans) != 0 or len(Eans) != 0: break

    samclasses.sleep()

  return (Rans, Wans, Eans)

def resolve(host, samaddr=samaddr):
  """Resolve I2P host name --> I2P Destination.
     Returns the same string if host is already a Destination."""
  if host.find('http://') == 0: host = host[len('http://'):]
  host = host.rstrip('/')
  if len(host) >= 256: return host
  S = samclasses.BaseSession(samaddr)
  ans = S._namelookup(host)
  S.close()
  return ans

def _exchange_data(A, B):
  """Exchanges data A <-> B between open stream sockets A and B."""
  # FIXME: There's recv errors that we should be shutting
  #        down sockets for, but this seems to work OK.
  err = None
  try:
    # Send data from A -> B while available.
    while True:
      # A -> B.
      A.setblocking(False)
      try: s = A.recv(1024)
      except Exception, e: s = None
      if s == '': raise ClosedError
      if s == None:
        # Stop sending A -> B.
        break
      else:
        B.setblocking(True)
        B.sendall(s)
  except Exception, e:
    err = e

  try:
    # Send data from B -> A while available.
    while True:
      # B -> A.
      B.setblocking(False)
      try: s = B.recv(1024)
      except Exception, e: s = None
      if s == '': raise ClosedError
      if s == None:
        # Stop sending B -> A.
        break
      else:
        A.setblocking(True)
        A.sendall(s)
  except Exception, e:
    err = e

  # Re-raise error after finishing communications both ways.
  if err != None: raise err

def _test_connected(B):
  """Raises an error if socket B is not yet connected."""
  [Rlist, Wlist, Elist] = select([B], [B], [B], 0.0)
  if len(Wlist) == 0:
    raise ValueError('socket not yet connected')

class Tunnel:
  def __init__(self, receive, make_send, nconnect=-1, timeout=60.0):
    """A Tunnel relays connections from a 'receive' socket to one
       or more 'send' sockets.  The receive socket must be bound
       and listening.  For each incoming connection, a new send
       socket is created by calling make_send().  Data is then
       exchanged between the created streams until one socket is
       closed.  nconnect is the maximum number of simultaneous
       connections (-1 for infinite), and timeout is the time that
       a single connection can last for (None allows a connection
       to last forever).

       Sockets must accept stream traffic and support the Python
       socket interface.  A separate daemonic thread is created to
       manage the tunnel.  For high performance, make_send() should
       make a socket and connect in non-blocking mode (you should
       catch and discard the sam.BlockError or socket.error due to
       executing connect on a non-blocking socket).

       Security Note:
       A firewall is needed to maintain the end user's anonymity.
       An attacker could keep a tunnel socket open by pinging it
       regularly.  The accepted sockets from 'receive' must prevent
       this by closing down eventually.

       Socket errors do not cause the Tunnel to shut down.
    """

    self.receive = receive
    self.make_send = make_send
    self.receive.setblocking(False)
    self.alive = True
    self.nconnect = nconnect
    self.timeout = timeout
    T = threading.Thread(target=self._run, args=())
    T.setDaemon(True)
    T.start()

  def _run(self):
    """Manage the tunnel in a separate thread."""
    tunnels = []

    while True:
      # Look for a new connection
      if self.nconnect < 0 or len(tunnels) < self.nconnect:
        (A, B) = (None, None)
        try:
          (A, ignoredest) = self.receive.accept()
          A.setblocking(False)
          B = self.make_send()
          B.setblocking(False)
          if self.timeout != None: t = time.time() + self.timeout
          else: t = None
          tunnels.append((A, B, t))
        except Exception, e:
          try:
            if A != None:
              A.setblocking(False); A.close()
          except Exception, e: pass
          try: 
            if B != None:
              B.setblocking(False); B.close()
          except Exception, e: pass

      # Send data between existing connections
      new_tunnels = []
      for (A, B, t) in tunnels:
        # For each connection pair, send data.
        try:
          if t != None: assert time.time() <= t
          # Test whether B is successfully connected
          _test_connected(B)

          # Send A <-> B.
          _exchange_data(A, B)

          if self.timeout != None: t = time.time() + self.timeout
          else: t = None
          new_tunnels.append((A, B, t))
        except Exception, e:
          # Catch errors.  Kill the connection if it's been at
          # least timeout seconds since last non-erroneous call
          # to _exchange_data, or if stream was closed.  This
          # allows stream-not-finished-connecting errors to be
          # dropped within the timeout.
          time_ok = True
          if self.timeout != None:
            if time.time() > t: time_ok = False
          if time_ok and not isinstance(e, ClosedError):
            # Don't kill connection yet
            new_tunnels.append((A, B, t))
          else:
            # We've only gotten errors for 'timeout' s.
            # Drop the connection.
            try: A.setblocking(False); A.close()
            except Exception, e: pass
            try: B.setblocking(False); B.close()
            except Exception, e: pass
      tunnels = new_tunnels
      time.sleep(0.01)

      # Shut down all connections if self.close() was called.
      if not self.alive:
        for (A, B, t) in tunnels:
          try: A.setblocking(False); A.close()
          except: pass
          try: B.setblocking(False); B.close()
          except: pass
        break

  def close(self):
    """Close all connections made for this tunnel."""
    self.alive = False

class TunnelServer(Tunnel):
  dest = property(doc='I2P Destination of server.')
  session = property(doc='Session name for server.')
  def __init__(self, session, port, samaddr=samaddr, nconnect=-1,
               timeout=None, **kwargs):
    """Tunnels incoming SAM streams --> localhost:port.

       nconnect and timeout are the maximum number of connections
       and maximum time per connection.  All other arguments are
       passed to sam.socket().  This call blocks until the tunnel
       is ready."""
    S = socket(session, SOCK_STREAM, samaddr, **kwargs)
    S.listen(64)
    self.session = session
    self.dest = S.dest
    def make_send():
      C = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
      C.setblocking(False)
      try: C.connect(('127.0.0.1', port))
      except: pass   # Ignore 'would have blocked' error
      return C
    Tunnel.__init__(self, S, make_send, nconnect, timeout)

class TunnelClient(Tunnel):
  remotedest = property(doc='Remote Destination.')
  dest = property('Local Destination used for routing.')
  session = property('Session name for local Destination.')
  def __init__(self, session, port, dest, samaddr=samaddr,
               nconnect=-1, timeout=None, **kwargs):
    """Tunnels localhost:port --> I2P Destination dest.

       A session named 'session' is created locally, for purposes
       of routing to 'dest'.  nconnect and timeout are the maximum
       number of connections and maximum time per connection.  All
       other arguments are passed to sam.socket().  This call blocks
       until the tunnel is ready."""
    S = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
    S.bind(('', port))
    S.listen(4)
    obj = socket(session, SOCK_STREAM, samaddr, **kwargs)
    self.session = session
    self.dest = obj.dest
    def make_send():
      C = socket(session, SOCK_STREAM, samaddr, **kwargs)
      C.setblocking(False)
      try: C.connect(dest)
      except: pass   # Ignore 'would have blocked' error
      return C
    Tunnel.__init__(self, S, make_send, nconnect, timeout)

# --------------------------------------------------
# End of File
# --------------------------------------------------
