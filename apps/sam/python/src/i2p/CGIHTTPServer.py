#! /usr/bin/env python

"""
Emulation of Python CGIHTTPServer module using I2P sockets.

The Python module is described at
http://www.python.org/doc/current/lib/module-CGIHTTPServer.html

To get a server going, use:

  >>> from i2p import CGIHTTPServer
  >>> CGIHTTPServer.test().

Consult the documentation for function test() to change basic
server settings, such as the session name.

A fully customizable example:

  >>> from i2p import BaseHTTPServer, CGIHTTPServer
  >>> session = "mytestxxx.i2p"      # SAM session name
  >>> class MyServer(BaseHTTPServer.HTTPServer): pass
  >>> class MyRequestHandler(CGIHTTPServer.CGIHTTPRequestHandler): pass
  >>> httpd = MyServer(session, MyRequestHandler)
  >>> httpd.socket.dest
  (Base64 Destination of server)
  >>> httpd.serve_forever()

"""

# By aum.
__all__ = ["CGIHTTPRequestHandler", "test"]

# Hack to keep Python from importing from current directory:
# Use pylib package, then use = signs instead of from x import y.
import pylib
CGIHTTPServer = pylib.CGIHTTPServer
nobody_uid = CGIHTTPServer.nobody_uid
executable = CGIHTTPServer.executable

import sys
import i2p.BaseHTTPServer
import i2p.SimpleHTTPServer

HTTPServer = i2p.BaseHTTPServer.HTTPServer
class CGIHTTPRequestHandler(CGIHTTPServer.CGIHTTPRequestHandler):
    """
    Same interface as Python class
    CGIHTTPServer.CGIHTTPRequestHandler.
    """

def test(HandlerClass = CGIHTTPRequestHandler,
         ServerClass = i2p.BaseHTTPServer.HTTPServer,
         session = "mytestxxx.i2p"):
    """
    Test the HTTP CGI request handler class.

    This runs an I2P TCP server under SAM session 'session'.
    If a single command line argument is given, the argument is used
    instead as the SAM session name.
    """
    if sys.argv[1:] and __name__ == '__main__':
        session = sys.argv[1]

    i2p.SimpleHTTPServer.test(HandlerClass, ServerClass,
                              session=session)

if __name__ == '__main__':
    test()

