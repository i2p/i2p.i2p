#! /usr/bin/env python

# ---------------------------------------------------
# raw_noblock.py: Non-blocking raw server
# ---------------------------------------------------

from i2p import socket
import time

S = socket.socket('Eve', socket.SOCK_RAW)
print 'Serving at:', S.dest
S.setblocking(False)

while True:
  try:
    data = S.recv(1000)                # Read packet
    print 'Got', data
  except socket.BlockError:               # No data available
    pass
  time.sleep(0.01)                     # Reduce CPU usage