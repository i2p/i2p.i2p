#! /usr/bin/env python

# -----------------------------------------------
# datagram_server.py: Datagram server
# -----------------------------------------------

from i2p import socket

S = socket.socket('Eve', socket.SOCK_DGRAM)
print 'Serving at:', S.dest

while True:
  (data, dest) = S.recvfrom(1000)    # Read packet
  print 'Got', data, 'from', dest
  S.sendto('Hi client!', 0, dest)
