
----------------------------------------
Python-I2P v0.91
----------------------------------------

Python-I2P is a Python interface to I2P.

All files in this directory and subdirectories
have been placed in the public domain.

----------------------------------------
Quick Start
----------------------------------------

Install:

  python setup.py install

Use:

  >>> from i2p import socket
  >>> s = socket.socket('Alice', socket.SOCK_STREAM)
  >>> s.connect('duck.i2p')
  >>> s.send('GET / HTTP/1.0\r\n\r\n')
  >>> s.recv(1000)
  (Response from duck.i2p)

See the src/examples/ directory for more code examples.

----------------------------------------
Full Start
----------------------------------------

See the docs directory for HTML documentation.
