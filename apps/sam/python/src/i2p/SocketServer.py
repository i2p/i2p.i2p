
"""
Emulation of Python SocketServer module using I2P sockets.

The Python module is described at
http://www.python.org/doc/current/lib/module-SocketServer.html

"""

# By aum.

# Hack to keep Python from importing from current directory:
# Use pylib package, then use = signs instead of from x import y.
import pylib
SocketServer = pylib.SocketServer

import i2p.socket
class BaseServer(SocketServer.BaseServer):
    pass
class TCPServer(SocketServer.TCPServer, BaseServer):
    
    socket_type = i2p.socket.SOCK_STREAM
    
    def __init__(self, session, RequestHandlerClass):
        """
        Constructor.  May be extended, do not override.

        The 'session' argument indicates the SAM session
        name that should be used for the server.  See module
        i2p.socket for details on SAM sessions.
        """
        BaseServer.__init__(self, session, RequestHandlerClass)
    
        #self.socket = socket.socket(self.address_family,
        #                            self.socket_type)
        self.session = session
        self.socket = i2p.socket.socket(session, self.socket_type)
    
        self.server_bind()
        self.server_activate()

class UDPServer(TCPServer, SocketServer.UDPServer):
    pass

class ForkingMixIn(SocketServer.ForkingMixIn):
    pass

class ThreadingMixIn(SocketServer.ThreadingMixIn):
    pass

class ForkingUDPServer(ForkingMixIn, UDPServer):
    pass

class ForkingTCPServer(ForkingMixIn, TCPServer):
    pass

class ThreadingUDPServer(ThreadingMixIn, UDPServer):
    pass

class ThreadingTCPServer(ThreadingMixIn, TCPServer):
    pass

class BaseRequestHandler(SocketServer.BaseRequestHandler):
    pass

class StreamRequestHandler(SocketServer.StreamRequestHandler):
    pass

class DatagramRequestHandler(SocketServer.DatagramRequestHandler):
    pass


