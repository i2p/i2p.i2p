#! /usr/bin/env python

import SimpleHTTPServer

import I2PBaseHTTPServer

__version__ = "0.1.0"

__all__ = ["SimpleHTTPRequestHandler"]

HTTPServer = I2PBaseHTTPServer.HTTPServer
class SimpleHTTPRequestHandler(SimpleHTTPServer.SimpleHTTPRequestHandler):
    pass

def test(HandlerClass = SimpleHTTPRequestHandler,
         ServerClass = I2PBaseHTTPServer.BaseHTTPServer):
    I2PBaseHTTPServer.test(HandlerClass, ServerClass)

if __name__ == '__main__':
    test()
