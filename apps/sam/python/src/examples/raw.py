#! /usr/bin/env python

# -----------------------------------------------
# raw.py: Raw client
# -----------------------------------------------

from i2p import socket

dest = socket.resolve('yourserver.i2p')      # Send to dest
S = socket.socket('Carol', socket.SOCK_RAW)
S.sendto('Hello packet', 0, dest)
