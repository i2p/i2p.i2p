#! /usr/bin/env python

# -----------------------------------------------
# stream.py: Simple stream client
# -----------------------------------------------

from i2p import socket
        
S = socket.socket('Alice', socket.SOCK_STREAM)
S.connect('duck.i2p')
S.send('GET / HTTP/1.0\r\n\r\n')      # Send request
print S.recv(1000)                    # Read up to 1000 bytes
