#! /usr/bin/env python

import CGIHTTPServer
from CGIHTTPServer import nobody_uid, executable

import I2PBaseHTTPServer, I2PSimpleHTTPServer

HTTPServer = I2PBaseHTTPServer.HTTPServer
class CGIHTTPRequestHandler(CGIHTTPServer.CGIHTTPRequestHandler):
    pass
def test(HandlerClass = CGIHTTPRequestHandler,
         ServerClass = I2PBaseHTTPServer.HTTPServer):
    I2PSimpleHTTPServer.test(HandlerClass, ServerClass)

if __name__ == '__main__':
    test()

