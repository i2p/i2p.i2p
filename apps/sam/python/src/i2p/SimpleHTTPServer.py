#! /usr/bin/env python

"""
Emulation of Python SimpleHTTPServer module using I2P sockets.

The Python module is described at
http://www.python.org/doc/current/lib/module-SimpleHTTPServer.html

To get a server going, use:

  >>> from i2p import SimpleHTTPServer
  >>> SimpleHTTPServer.test().

Consult the documentation for function test() to change basic
server settings, such as the session name.

A fully customizable example:

  >>> from i2p import BaseHTTPServer, SimpleHTTPServer
  >>> session = "mytestxxx.i2p"      # SAM session name
  >>> class MyServer(BaseHTTPServer.HTTPServer): pass
  >>> class MyRequestHandler(SimpleHTTPServer.SimpleHTTPRequestHandler): pass
  >>> httpd = MyServer(session, MyRequestHandler)
  >>> httpd.socket.dest
  (Base64 Destination of server)
  >>> httpd.serve_forever()

"""

# By aum.

# Hack to keep Python from importing from current directory:
# Use pylib package, then use = signs instead of from x import y.
import pylib
SimpleHTTPServer = pylib.SimpleHTTPServer

import sys
import i2p.BaseHTTPServer

__version__ = "0.1.0"

__all__ = ["SimpleHTTPRequestHandler", "test"]

HTTPServer = i2p.BaseHTTPServer.HTTPServer
class SimpleHTTPRequestHandler(SimpleHTTPServer.SimpleHTTPRequestHandler):
    """
    Same interface as Python class
    SimpleHTTPServer.SimpleHTTPRequestHandler.
    """

def test(HandlerClass = SimpleHTTPRequestHandler,
         ServerClass = i2p.BaseHTTPServer.HTTPServer,
         session = "mytestxxx.i2p"):
    """
    Test the HTTP simple request handler class.

    This runs an I2P TCP server under SAM session 'session'.
    If a single command line argument is given, the argument is used
    instead as the SAM session name.
    """
    if sys.argv[1:] and __name__ == '__main__':
        session = sys.argv[1]

    i2p.BaseHTTPServer.test(HandlerClass, ServerClass,
                            session=session)

if __name__ == '__main__':
    test()
