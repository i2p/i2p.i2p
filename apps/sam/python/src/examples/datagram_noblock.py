#! /usr/bin/env python

# ---------------------------------------------------
# datagram_noblock.py: Non-blocking datagram server
# ---------------------------------------------------

from i2p import socket
import time

S = socket.socket('Eve', socket.SOCK_DGRAM)
print 'Serving at:', S.dest
S.setblocking(False)

while True:
  try:
    (data, dest) = S.recvfrom(1000)    # Read packet
    print 'Got', data, 'from', dest
    S.sendto('Hi client!', 0, dest)
  except socket.BlockError:            # No data available
    pass
  time.sleep(0.01)                     # Reduce CPU usage
