
# -------------------------------------------------------------
# tunnel.py: Python SAM Tunnel classes
# -------------------------------------------------------------

"""Exchange data between I2P and regular TCP sockets."""

import time, threading, sys

import i2p
import i2p.socket
import i2p.select
from i2p.pylib import socket as pysocket   # Import Python socket

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
      if s == '': raise i2p.socket.ClosedError
      if s == None:
        # No data available.  Stop sending A -> B.
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
      if s == '': raise i2p.socket.ClosedError
      if s == None:
        # No data available.  Stop sending B -> A.
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
  [Rlist, Wlist, Elist] = i2p.select.select([B], [B], [B], 0.0)
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
       catch and discard the i2p.socket.BlockError or socket.error
       due to executing connect on a non-blocking socket).

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
          if time_ok and not isinstance(e, i2p.socket.ClosedError):
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
  def __init__(self, session, port, samaddr=i2p.socket.samaddr,
               nconnect=-1, timeout=None, **kwargs):
    """Tunnels incoming SAM streams --> localhost:port.

       nconnect and timeout are the maximum number of connections
       and maximum time per connection.  All other arguments are
       passed to i2p.socket.socket().  This call blocks until the
       tunnel is ready."""
    S = i2p.socket.socket(session, i2p.socket.SOCK_STREAM, samaddr,
                          **kwargs)
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
  def __init__(self, session, port, dest, samaddr=i2p.socket.samaddr,
               nconnect=-1, timeout=None, **kwargs):
    """Tunnels localhost:port --> I2P Destination dest.

       A session named 'session' is created locally, for purposes
       of routing to 'dest'.  nconnect and timeout are the maximum
       number of connections and maximum time per connection.  All
       other arguments are passed to i2p.socket.socket().  This call
       blocks until the tunnel is ready."""
    S = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
    S.bind(('', port))
    S.listen(4)
    obj = i2p.socket.socket(session, i2p.socket.SOCK_STREAM, samaddr,
                            **kwargs)
    self.session = session
    self.dest = obj.dest
    def make_send():
      C = i2p.socket.socket(session, i2p.socket.SOCK_STREAM, samaddr,
                            **kwargs)
      C.setblocking(False)
      try: C.connect(dest)
      except: pass   # Ignore 'would have blocked' error
      return C
    Tunnel.__init__(self, S, make_send, nconnect, timeout)
