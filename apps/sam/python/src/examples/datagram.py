#! /usr/bin/env python

# -----------------------------------------------
# datagram.py: Datagram client
# -----------------------------------------------

from i2p import socket

print 'Sending a packet to yourserver.i2p...'

dest = socket.resolve('yourserver.i2p')
S = socket.socket('Bob', socket.SOCK_DGRAM)
S.sendto('Hello packet', 0, dest)

# Get packet up to 1000 bytes -- the rest is discarded.
(data, dest) = S.recvfrom(1000)

print data
