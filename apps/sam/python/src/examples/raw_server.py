#! /usr/bin/env python

# -----------------------------------------------
# raw_server.py: Raw server
# -----------------------------------------------

from i2p import socket

S = socket.socket('Eve', socket.SOCK_RAW)
print 'Serving at:', S.dest

while True:
  data = S.recv(1000)            # Read packet
  print 'Got', data
