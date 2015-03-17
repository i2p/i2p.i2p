#!/usr/bin/python


# echo server
# accepts a socket on specified port, writes on stdout and send back incoming data

import socket
import sys

if len(sys.argv)>=2 :
	port = eval(sys.argv[1])
else :  port = 25000

#create an INET, STREAMing socket
serversocket = socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
#bind the socket to a public host, 
# and a well-known port
serversocket.bind(("0.0.0.0", port))
    #become a server socket
serversocket.listen(1)


        #accept connections from outside
(clientsocket, address) = serversocket.accept()
        #now do something with the clientsocket
        #in this case, we'll pretend this is a threaded server

i = 0
while 1 :
    chunk = clientsocket.recv(1024)
    i = i + 1
    sys.stdout.write(str(i)+' '+chunk)
    if not chunk: break
    clientsocket.send(str(i)+' '+chunk)


clientsocket.close()

print

