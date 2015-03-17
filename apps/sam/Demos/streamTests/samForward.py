#!/usr/bin/python

import socket
import sys

# create a master SAM stream session that opens a destination in I2P world
# then open another session that tells SAM to forward incoming connections
#  to the specified address
#
# usage :
#        ./samForward.py [ silent [ port [ sessionName [ host ] ] ] ]
#
#           silent : should the first line of incoming socket contain the peer destination (true or false)
#           port : port to which connections are forwarded (default : 25000)
#           sessionName : session id (default : "forward")
#           host : host to which connections are forwarded (default : this host)

if len(sys.argv)>=2 :
	silent = " SILENT="+sys.argv[1]
else :  silent = " SILENT=false"

if len(sys.argv)>=3 :
	port = " PORT="+sys.argv[2]
else :  port = " PORT=25000"

if len(sys.argv)>=4 :
	name = " ID="+sys.argv[3]
else :  name = " ID=forward"

if len(sys.argv)>=5 :
	host = " HOST="+sys.argv[4]
else :  host = ""




sess = socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sess.connect(("127.0.0.1",7656));
sess.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sess.recv(1000))
sess.send("SESSION CREATE STYLE=STREAM"+name+" DESTINATION=tYhjbFlFL38WFuO5eCzTvE0UBr4RfaqWMKlekGeMoB-Ouz7nYaWfiS-9j3jMiZT7FH~pwdmoSREOs2ZbXK84sR59P~pPfeCMxnJrk57f3U9uKzXkesjkKWYco3YAGs-G8sw8Fu2FBx0Do57yBdA9~j8Zq6pMjmgPBXCLuXG3vo0Z8zUWCjApJyFY6OXYopHck9Fz9vKy7YhC6zXFHfEuNHVkAooduiLd~aCoGij0TW3lH2rTVU-lx-DUdi6edxQ5-RvDNkXfikvytoCpRkivbNVytjCJLk~7RNU4FpBD20wTZWNJmEG3OY3cjNjawJVFdNjtgczh9K7gZ7ad-NjVjZVhXEj1lU8mk~vAH-2QE5om8dstWUwWoNDwmVDlvIJNKzQmahG~VrpFexFHXO0n3fKIXcSgWGOHDExM8w9neCt7AxUjxPDtXXuYNW~bRwcfiL-C9~z4K9rmwiTPZX0lmsToSXTF28l7WAoj~TMT9kZAjQeFRRWU5oW5oxVuonVvAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABngJSS8xMyF4t82otZmCDhrKjbm-QLMtOLoumwR28ebDHEd4clF6O7aRa3d3yRH7p\n")
sys.stdout.write(sess.recv(1000))

sock =  socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sock.connect(("127.0.0.1",7656));
sock.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sock.recv(1000))
sock.send("STREAM FORWARD" + name + host + port + silent + "\n")
sys.stdout.write(sock.recv(1000))

l=0
while 1 :
    chunk = sock.recv(100)
    sys.stdout.write(chunk)
    if not chunk : break
print "Forward socket closed"
l=0
while 1 :
    chunk = sess.recv(100)
    sys.stdout.write(chunk)
    if not chunk : break

