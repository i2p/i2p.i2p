
# -------------------------------------------------------------
# select.py: Emulation of Python select module.
# -------------------------------------------------------------

"""
I2P Python API - Emulation of Python select module.
"""

import time

import i2p.socket
from i2p.pylib import select as pyselect   # Import Python select

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
    """Get a unique number for each object."""
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
    for F, mask in self.fds.values():
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
     information.

     Polling flags specified in this module:
     - POLLIN
     - POLLOUT
     - POLLERR
     - POLLHUP
     - POLLNVAL
     - POLLPRI
"""
  return Poll()

def _has_data(S):
  """True if the given I2P socket has data waiting."""
  try:
    S.recv(1, i2p.socket.MSG_PEEK | i2p.socket.MSG_DONTWAIT)
    return True
  except:
    return False

def _noblock_select(readlist, writelist, errlist):
  """Makes a single query of the given sockets, like
     select() with timeout 0.0."""
  Rans = []
  Wans = []
  Eans = []

  # Check for read availability.
  for R in readlist:
    if isinstance(R, int) or hasattr(R, 'fileno'):
      # Python socket
      if len(pyselect.select([R], [], [], 0.0)[0]) > 0:
        Rans.append(R)
    else:
      # SAM Socket
      if _has_data(R):
        Rans.append(R)
#      if R.type == i2p.socket.SOCK_STREAM:
#        try:
#          R._verify_connected()
#          Rans.append(R)
#        except:
#          pass
#      else:
#        if len(R.sessobj) > 0: Rans.append(R)

  # Check for write availability.
  for W in writelist:
    if isinstance(W, int) or hasattr(W, 'fileno'):
      # Python socket
      if len(pyselect.select([], [W], [], 0.0)[1]) > 0:
        Wans.append(W)
    else:
      # SAM Socket
      if W.type == i2p.socket.SOCK_STREAM:
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
      if E.type == i2p.socket.SOCK_STREAM:
        try:
          # FIXME: Use a ._get_error() function for errors.
          # Socket can only have an error if it connected.
          E._verify_connected()
          if E.sessobj.err != None:
            Eans.append(E)
        except:
          pass

  return (Rans, Wans, Eans)


def select(readlist, writelist, errlist, timeout=None):
  """Performs a select call.  Works on SAM sockets and Python
     sockets.  See select.select() in the Python library for more
     information."""

  if timeout != None: end = time.time() + timeout
  while True:
    # FIXME: Check performance.
    # Use pyselect.poll for Python sockets, if needed for speed.
    (Rans, Wans, Eans) = _noblock_select(readlist,writelist,errlist)

    if timeout != None and time.time() >= end: break
    if len(Rans) != 0 or len(Wans) != 0 or len(Eans) != 0:
      # One or more sockets are ready.
      if timeout != 0.0:
        # Check again, because sockets may have changed state while
        # we did _noblock_select (it's safer to check twice, since
        # they usually go from no data => data ready, and so forth).
        (Rans, Wans, Eans) = _noblock_select(readlist, writelist,
                                             errlist)
        return (Rans, Wans, Eans)

    time.sleep(0.01)

  return (Rans, Wans, Eans)
