#! /usr/bin/env python

import BaseHTTPServer

import i2p.sam

import I2PSocketServer

import sys
import BaseHTTPServer

import i2p.sam
import I2PSocketServer

__version__ = "0.3"

__all__ = ["HTTPServer", "BaseHTTPRequestHandler"]

DEFAULT_ERROR_MESSAGE = BaseHTTPServer.DEFAULT_ERROR_MESSAGE

class HTTPServer(I2PSocketServer.TCPServer, BaseHTTPServer.HTTPServer):
    pass
class BaseHTTPRequestHandler(
        I2PSocketServer.StreamRequestHandler,
        BaseHTTPServer.BaseHTTPRequestHandler):
    pass
def test(HandlerClass = BaseHTTPRequestHandler,
         ServerClass = HTTPServer, protocol="HTTP/1.0"):
    """Test the HTTP request handler class.

    This runs an HTTP server on port 8000 (or the first command line
    argument).

    """

    if sys.argv[1:]:
        server_address = sys.argv[1]
    else:
        server_address = "mytestxxx.i2p"

    HandlerClass.protocol_version = protocol
    httpd = ServerClass(server_address, HandlerClass)

    print "Serving HTTP on", server_address, "..."
    print "Destination follows:"
    print httpd.socket.dest
    httpd.serve_forever()

if __name__ == '__main__':
    test()
