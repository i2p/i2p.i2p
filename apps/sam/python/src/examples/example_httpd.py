#! /usr/bin/env python

myServerAddress = "mytestxxx.i2p"

from i2p import I2PBaseHTTPServer, I2PCGIHTTPServer

class MyServer(I2PBaseHTTPServer.HTTPServer):
    pass

class MyRequestHandler(I2PCGIHTTPServer.CGIHTTPRequestHandler):
    pass

def runServer():

    httpd = MyServer(myServerAddress, MyRequestHandler)
    print "MyServer: local address = %s" % myServerAddress
    print "MyServer: dest = %s" % httpd.socket.dest
    httpd.serve_forever()

if __name__ == '__main__':
    runServer()

