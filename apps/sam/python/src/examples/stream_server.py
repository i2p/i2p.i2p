#! /usr/bin/env python

# -----------------------------------------------
# stream_server.py: Simple stream server
# -----------------------------------------------

import i2p
from i2p import socket

S = socket.socket('Dave', socket.SOCK_STREAM)
S.listen(10)                      # Queue up to 10 connections
print 'Serving at:', S.dest

while True:
  try:
    (C, remotedest) = S.accept()  # Get a connection
    f = C.makefile()              # File object
    req = f.readline()            # Read HTTP request

    s = '<h1>Hello!</h1>'         # String to send back

    f.write('HTTP/1.0 200 OK\r\nContent-Type: text/html' +
    '\r\nContent-Length: ' + str(int(len(s))) + '\r\n\r\n' + s)

    f.close()                     # Close file
    C.close()                     # Close connection
  except socket.Error, e:
    # Recover from socket errors
    print 'Warning:', str(e)
