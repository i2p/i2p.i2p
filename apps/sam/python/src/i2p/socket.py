
# -------------------------------------------------------------
# socket.py: Emulation of Python socket module.
# -------------------------------------------------------------

"""
Emulation of Python socket module using SAM.
"""

import i2p

import samclasses, threading, time, copy, Queue, thread
from i2p.pylib import socket as pysocket   # Import Python socket
from i2p.pylib import select as pyselect   # Import Python select

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

       - in_depth  - depth of incoming tunnel (default 2)
       - out_depth - depth of outgoing tunnel (default 2)

     A single session may be shared by more than one socket, if
     the sockets are the same type, and if the sockets are
     created within the same Python process.  The socket
     objects are multithread-safe.

     Examples:
       >>> a = i2p.socket('Alice', i2p.SOCK_STREAM)
       >>> b = i2p.socket('Bob',   i2p.SOCK_DGRAM,
                          in_depth=2, out_depth=5)

     The created object behaves identically to a socket from
     module socket, with the following exceptions:

       - I2P Destinations are used as address arguments [1].
       - bind is a no-op: sockets are always bound.
       - send* methods send all data and are non-blocking.

     A given session name can only be open in a single Python
     program at a time.  If you need to overcome this
     limitation, consider patching I2P.

     [1].
     Alternatively, a host name can be used as an address.
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
    """Accept an incoming connection.  The socket must be type
       SOCK_STREAM, and listen() must be called prior to this
       command.  The return value is (conn, remotedest), where
       conn is a new socket object made for the connection, and
       remotedest is the remote Destination from which the
       connection was made.

       Example:

         >>> from i2p import socket
         >>> s = socket.socket('Alice', socket.SOCK_STREAM)
         >>> s.listen(10)

       This prepares the server.  Now accept an incoming connection:

         >>> c, remotedest = s.accept()
         >>> c.send('hello world!')

       If accept() is called on a socket that is in non-blocking
       mode or has a timeout, i2p.socket.BlockError or
       i2p.socket.Timeout may be raised.  This indicates that no
       incoming connection is currently available."""

    self._verify_open()
    self._verify_not_connected()
    # Raises BlockError or Timeout if not ready.
    C = _wrap_stream(self.sessobj.accept(self.timeout), self)
    return (C, C.remotedest)

  def bind(self, address):
    """Does nothing.  Provided for compatibility with the Python
       socket command bind(), which binds a server to a port."""
    self._verify_open()
    self._verify_not_connected()

  def close(self):
    """Closes the socket.  It is an error to call any method
       other than recv() or recvfrom() on a closed socket.
       For streams, the receive methods return data that was
       received prior to the closing of the socket.  For
       datagram and raw sockets, the receive methods cannot
       be used on a closed socket."""
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
    """
    Connect to a remote dest, identified in local SAM bridge's hosts
    file as host 'address'.

    For example:
      >>> s.connect('duck.i2p')

    Alternatively, you can use a full base64 Destination:

    Example:
      >>> s.connect('238797sdfh2k34kjh....AAAA')

    If connect() is called on a socket that is in non-blocking
    mode or has a timeout, i2p.socket.BlockError or
    i2p.socket.Timeout may be raised.  This indicates that the
    connection is still being initiated.  Use i2p.select.select()
    to determine when the connection is ready.
    """
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
              'i2p.select.select() to find when socket is connected')
        else: raise Timeout('timed out.  use i2p.select.select()' +
                            ' to find when socket is connected')

    finally: self.lock.release()

  def connect_ex(self, address):
    """Like connect(), but return any error that is raised.
       Returns None if no error is raised."""
    try: self.connect(address)
    except i2p.Error, e: return e

  # Don't implement fileno(), as we don't have a real file handle.

  def getpeername(self):
    """Get the remote Destination associated with the socket.
       This is equivalent to s.remotedest, and is provided for
       compatibility with the Python socket module."""
    self._verify_connected()
    return self.remotedest

  def getsockname(self):
    """Get the local Destination associated with the socket.
       This is equivalent to s.dest, and is provided for
       compatibility with the Python socket module."""
    return self.dest

  def listen(self, backlog):
    """Listen for connections made to the socket.
       This method must be called before accept().
       The backlog argument specifies the maximum number of
       queued incoming connections."""
    self._verify_open()
    self._verify_not_connected()
    self.sessobj.listen(backlog)

  def makefile(self, mode='r', bufsize=-1):
    """Return a file object for the socket.
       See socket.makefile() in the Python documentation for
       more information."""
    self._verify_open()
    self._verify_connected()
    return pysocket._fileobject(self, mode, bufsize)

  def recv(self, bufsize, flags=0):
    """Receive string data from the socket.

       The maximum amount of data to be received is given by
       bufsize.  If bufsize is zero, this function returns
       an empty string immediately.  If bufsize is nonzero,
       this function blocks until at least one character is
       available for reading.  If the socket has been closed,
       an empty string is returned as an end of file indicator.

       If recv() is called on a socket that is in non-blocking
       mode or has a timeout, i2p.socket.BlockError or
       i2p.socket.Timeout will be raised if data is not available
       within the given timeframe.

       For a datagram or raw socket, the first bufsize characters
       of the packet are read, and the remainder of the packet is
       discarded.  To read the entire packet, use bufsize = -1.

       For datagram and raw sockets, the packet may originate from
       any Destination.  Use recvfrom() with datagrams to determine
       the Destination from which the packet was received.

       The flags argument can be a bitwise OR of MSG_PEEK,
       MSG_WAITALL, and/or MSG_DONTWAIT.  MSG_PEEK indicates that
       any data read should not be removed from the socket's
       incoming buffer.  MSG_WAITALL indicates to wait for exactly
       bufsize characters or an error.  MSG_DONTWAIT indicates
       that the recv() command should not block execution.
       """
       
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
    """Like recv(), but returns a tuple (data, remoteaddr), where
       data is the string data received, and remoteaddr is the
       remote Destination."""
    timeout = self.timeout
    (peek, waitall, dontwait) = \
      (flags & MSG_PEEK, flags & MSG_WAITALL, flags & MSG_DONTWAIT)
    if dontwait: timeout = 0.0

    if self.type == SOCK_STREAM:
      self._verify_connected()
      if bufsize < 0: raise ValueError('bufsize must be >= 0 for streams')
      return (self.sessobj.recv(bufsize, timeout, peek, waitall), \
              self.remotedest)
    else:
      if bufsize < -1:
        raise ValueError('bufsize must be >= -1 for packets')
      (data, addr) = self.sessobj.recv(timeout, peek)
      if bufsize == -1:
        return (data, addr)
      else:
        return (data[:bufsize], addr)

  def send(self, string, flags=0):
    """Sends string data to a remote Destination.

       For a stream, connect() must be called prior to send().
       Once close() is called, no further data can be sent, and
       the stream cannot be re-opened.

       For datagram and raw sockets, connect() only specifies
       a Destination to which packets are sent to.  send() will
       then send a packet to the given Destination.  connect()
       can be used multiple times.

       The send() command never blocks execution.  The flags
       argument is ignored.
    """

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
    """Identical to send()."""
    self.send(string)

  def sendto(self, string, flags, address):
    """Send a packet to the given Destination.

       Only valid for datagram and raw sockets.  The address
       argument should be either a name from the hosts file,
       or a base64 Destination.

       The sendto() command never blocks execution.  The flags
       argument is ignored.
    """
       
    self._verify_open()
    if not self.type in [SOCK_DGRAM, SOCK_RAW]:
      raise i2p.Error('operation not supported')
    if self.closed:
      raise i2p.Error('sendto operation on closed socket')
    address = resolve(address, self.samaddr)
    self.sessobj.send(string, address)

  def setblocking(self, flag):
    """Set blocking or non-blocking mode for the socket.

       If flag is True, any method called on the socket will
       hang until the method has completed.  If flag is False,
       all methods will raise i2p.socket.BlockError() if they
       cannot complete instantly.

       s.setblocking(False) is equivalent to s.settimeout(0);
       s.setblocking(True) is equivalent to s.settimeout(None).
    """
    if flag: self.timeout = None
    else: self.timeout = 0.0

  def settimeout(self, value):
    """Set a timeout for the socket.

       The value argument should be a timeout value in seconds,
       or None.  None is equivalent to an infinite timeout.

       A socket operation will raise a i2p.socket.Timeout if
       the operation cannot complete within in the specified
       time limit.
    """
    self.timeout = value

  def gettimeout(self):
    """Get the timeout value."""
    return self.timeout

  def __copy__(self):
    """Returns the original object."""
    return self

  def __deepcopy__(self, memo):
    """Returns the original object."""
    return self

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

# --------------------------------------------------
# End of File
# --------------------------------------------------
