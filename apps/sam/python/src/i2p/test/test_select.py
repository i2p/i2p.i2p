
# -----------------------------------------------------
# test_select.py: Unit tests for select.py.
# -----------------------------------------------------

# Make sure we can import i2p
import sys; sys.path += ['../../']

import time

import traceback, sys
from i2p import socket, select
import i2p.socket
import socket as pysocket

def minitest_select(rans, wans, eans, timeout,
                    f1=None, f4=None, c1=None, c4=None):
  """Mini-unit test for select (Python and I2P sockets).
     Calls f1() on socket S1, f4() on socket S4, uses select()
     timeout 'timeout'.  rans, wans, and eans should be lists
     containing indexes 1...6 of the sockets defined below.  The
     result of i2p.select.select() will be verified against these
     lists.  After this, calls c1() on S1, and c4() on S4."""
  S1 = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
  S2 = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_DGRAM)
  S3 = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_RAW)

  kw = {'in_depth':0, 'out_depth':0}
  S4 = socket.socket('Fella', socket.SOCK_STREAM, **kw)
  S5 = socket.socket('Boar',  socket.SOCK_DGRAM,  **kw)
  S6 = socket.socket('Gehka', socket.SOCK_RAW,    **kw)

  if f1: f1(S1)
  if f4: f4(S4)

  L = [S1, S2, S3, S4, S5, S6]

  start = time.time()
  ans  = select.select(L,  L,  L,  timeout)
  ans1 = select.select(L,  [], [], timeout)
  ans2 = select.select([], L,  [], timeout)
  ans3 = select.select([], [], L,  timeout)
  end = time.time()
  T = end - start

  ans  = [[L.index(x) + 1 for x in ans [i]] for i in range(3)]
  ans1 = [[L.index(x) + 1 for x in ans1[i]] for i in range(3)]
  ans2 = [[L.index(x) + 1 for x in ans2[i]] for i in range(3)]
  ans3 = [[L.index(x) + 1 for x in ans3[i]] for i in range(3)]

  assert ans1[0] == rans
  assert ans2[1] == wans
  assert ans3[2] == eans
  assert ans  == [rans, wans, eans]
  assert T < 4 * timeout + 0.1

  if c1: c1(S1)
  if c4: c4(S4)

def test_select():
  """Unit test for select (Python and I2P sockets)."""

  def connect1(S):
    """Connect regular Python socket to Google."""
    ip = pysocket.gethostbyname('www.google.com')
    S.connect((ip, 80))

  def connect4(S):
    """Connect I2P Python socket to duck.i2p."""
    S.connect('duck.i2p')

  def full1(S):
    """Connect regular Python socket to Google, and send."""
    connect1(S)
    S.sendall('GET / HTTP/1.0\r\n\r\n')
    S.recv(1)

  def full4(S):
    """Connect I2P Python socket to duck.i2p, and send."""
    connect4(S)
    S.sendall('GET / HTTP/1.0\r\n\r\n')
    S.recv(1)
    # Peek twice (make sure peek code isn't causing problems).
    S.recv(1, i2p.socket.MSG_PEEK | i2p.socket.MSG_DONTWAIT)
    S.recv(1, i2p.socket.MSG_PEEK | i2p.socket.MSG_DONTWAIT)
  
  def check(S):
    """Verify that three chars recv()d are 'TTP'."""
    assert S.recv(3) == 'TTP'

  try:
    for t in [0.0, 1.0]:
      minitest_select([], [2, 3, 5, 6], [], t)
      minitest_select([], [1, 2, 3, 4, 5, 6], [], t,
                      f1=connect1, f4=connect4)
      minitest_select([], [1, 2, 3, 5, 6], [], t,
                      f1=connect1)
      minitest_select([], [2, 3, 4, 5, 6], [], t,
                      f4=connect4)
      minitest_select([1, 4], [1, 2, 3, 4, 5, 6], [], t,
                      f1=full1, f4=full4, c1=check, c4=check)
  except:
    print 'Unit test failed for i2p.select.select().'
    traceback.print_exc(); sys.exit()
  print 'i2p.select.select():      OK'


if __name__ == '__main__':
  test_select()
