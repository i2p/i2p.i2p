import SocketServer

import i2p.sam
class BaseServer(SocketServer.BaseServer):
    pass
class TCPServer(SocketServer.TCPServer, BaseServer):
    
    socket_type = i2p.sam.SOCK_STREAM
    
    def __init__(self, server_address, RequestHandlerClass):
        """Constructor.  May be extended, do not override."""
        BaseServer.__init__(self, server_address, RequestHandlerClass)
    
        #self.socket = socket.socket(self.address_family,
        #                            self.socket_type)
        self.server_address = server_address
        self.socket = i2p.sam.socket(server_address, self.socket_type)
    
        self.server_bind()
        self.server_activate()
class UDPServer(TCPServer, SocketServer.UDPServer):

    pass
class ForkingMixIn(SocketServer.ForkingMixIn):
    
    pass
class ThreadingMixIn(SocketServer.ThreadingMixIn):
    
    pass
class ForkingUDPServer(ForkingMixIn, UDPServer): pass

class ForkingTCPServer(ForkingMixIn, TCPServer): pass
class ThreadingUDPServer(ThreadingMixIn, UDPServer): pass

class ThreadingTCPServer(ThreadingMixIn, TCPServer): pass

class BaseRequestHandler(SocketServer.BaseRequestHandler):
    pass

class StreamRequestHandler(SocketServer.StreamRequestHandler):
    
    pass
class DatagramRequestHandler(SocketServer.DatagramRequestHandler):
    
    pass


