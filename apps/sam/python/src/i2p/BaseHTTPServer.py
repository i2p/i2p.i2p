#! /usr/bin/env python

"""
Emulation of Python BaseHTTPServer module using I2P sockets.

The Python module is described at
http://www.python.org/doc/current/lib/module-BaseHTTPServer.html

To get a server going, use:

  >>> from i2p import BaseHTTPServer
  >>> BaseHTTPServer.test().

Consult the documentation for function test() to change basic
server settings, such as the session name.

A fully customizable example:

  >>> from i2p import BaseHTTPServer
  >>> session = "mytestxxx.i2p"      # SAM session name
  >>> class MyServer(BaseHTTPServer.HTTPServer): pass
  >>> class MyRequestHandler(BaseHTTPServer.BaseHTTPRequestHandler): pass
  >>> httpd = MyServer(session, MyRequestHandler)
  >>> httpd.socket.dest
  (Base64 Destination of server)
  >>> httpd.serve_forever()

"""

# By aum.

# Hack to keep Python from importing from current directory:
# Use pylib package, then use = signs instead of from x import y.
import pylib
BaseHTTPServer = pylib.BaseHTTPServer

import sys
import i2p.SocketServer

__version__ = "0.3"

__all__ = ["HTTPServer", "BaseHTTPRequestHandler", "test"]

DEFAULT_ERROR_MESSAGE = BaseHTTPServer.DEFAULT_ERROR_MESSAGE

class HTTPServer(i2p.SocketServer.TCPServer, BaseHTTPServer.HTTPServer):
    """
    Same interface as Python class
    BaseHTTPServer.HTTPServer.
    """

class BaseHTTPRequestHandler(
        i2p.SocketServer.StreamRequestHandler,
        BaseHTTPServer.BaseHTTPRequestHandler):
    """
    Same interface as Python class
    BaseHTTPServer.BaseHTTPRequestHandler.
    """

def test(HandlerClass = BaseHTTPRequestHandler,
         ServerClass = HTTPServer, protocol="HTTP/1.0",
         session = "mytestxxx.i2p"):
    """
    Test the HTTP request handler class.

    This runs an I2P TCP server under SAM session 'session'.
    If a single command line argument is given, the argument is used
    instead as the SAM session name.
    """

    if sys.argv[1:] and __name__ == '__main__':
        session = sys.argv[1]

    HandlerClass.protocol_version = protocol
    httpd = ServerClass(session, HandlerClass)

    print "Serving HTTP on", session, "..."
    print "Destination follows:"
    print httpd.socket.dest
    httpd.serve_forever()

if __name__ == '__main__':
    test()
