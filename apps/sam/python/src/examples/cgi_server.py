#! /usr/bin/env python

# -----------------------------------------------
# cgi_server.py: Simple CGI server
# -----------------------------------------------

myServerSession = "mytestxxx.i2p"

from i2p import BaseHTTPServer, CGIHTTPServer

class MyServer(BaseHTTPServer.HTTPServer):
    pass

class MyRequestHandler(CGIHTTPServer.CGIHTTPRequestHandler):
    pass

def runServer():

    httpd = MyServer(myServerSession, MyRequestHandler)
    print "MyServer: local SAM session = %s" % myServerSession
    print "MyServer: dest = %s" % httpd.socket.dest
    httpd.serve_forever()

if __name__ == '__main__':
    runServer()

