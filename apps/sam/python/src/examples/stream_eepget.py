#! /usr/bin/env python

# -----------------------------------------------
# stream_eepget.py: Get an eepsite using sockets
# -----------------------------------------------

from i2p import socket

S = socket.socket('Alice', socket.SOCK_STREAM)  
S.connect('duck.i2p')
S.send('GET / HTTP/1.0\r\n\r\n')          # Send request
f = S.makefile()                          # File object

while True:                               # Read header
  line = f.readline().strip()             # Read a line
  if line == '': break                    # Content begins

print f.read()                            # Read file object
