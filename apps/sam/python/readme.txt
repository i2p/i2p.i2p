
----------------------------------------
Python-I2P v0.9
----------------------------------------

Python-I2P is a Python interface to I2P.

All files in this directory and subdirectories
have been placed in the public domain by
Connelly Barnes.

----------------------------------------
Quick Start
----------------------------------------

Install:

  python setup.py install

Use:

  >>> from i2p import sam
  >>> s = sam.socket('Alice', sam.SOCK_STREAM)
  >>> s.connect('duck.i2p')
  >>> s.send('GET / HTTP/1.0\r\n\r\n')
  >>> s.recv(1000)
  (Response from duck.i2p)


----------------------------------------
Full Start
----------------------------------------

See the docs directory for HTML documentation.
