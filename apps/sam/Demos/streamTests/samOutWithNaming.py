#!/usr/bin/python


# open a I2P stream destination
# then open another stream that connects to the destination created by samForward.py or samIn.py
# then send bytes through the stream
# usage :
#        ./samOut.py [ silent [ sessionName ] ]
#
#           silent : should the first incoming after the connection request contain the connection status message (true or false)
#           sessionName : session id (default : "forward")

import socket
import sys
import time

if len(sys.argv)>=2 :
	silent = " SILENT="+sys.argv[1]
else :  silent = " SILENT=false"

if len(sys.argv)>=3 :
	name = " ID="+sys.argv[2]
else :  name = " ID=testOutStream"

sess = socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sess.connect(("127.0.0.1",7656));
sess.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sess.recv(1000))
sess.send("SESSION CREATE STYLE=STREAM"+name+" DESTINATION=EYUpJFeW9tiubXR0aOjvCJ~ndj3xN0Wn-ljuGdbpOEttPg7nj0VCTOQDJ~FAolzn9FIDdmR3VjM0OFFDT46Q5HN4vShXFE2VNC8e3~GjzxJfaJhijRC2R9oIOzsNlzKtInD2o9lh0PxPioNMCigwmgWuqlQHs4tjWeaYRAtooHxbrtuoCIhIdGfyVV-nAcPiyYbouKq3leETXE~4kBXm-LfWfyPtrv6OuDk3GBVVcthv19GYBmnl2YI8HpJjc-G-TvNkgYishjzIJyEW-Xrpy43R4ZBXlyQqnheGLlbOEY8NLDbyNHLRMMOGbcr~67SVE3Iw3RqQ3Dhrkq2FCaQwcDucfIUCCbOfCZgu0hlnCkS42xsUvegQeiwMxbdI~h9v7vcR3yFFOrHX6WQvIZSbFLKNGArGJcfmOJVLqw1wTC4AgYXjk3csVDPd-QWbMXOuodyBgrg27Ds2BBYTsVXWskoo6ASsMIQZ6jMfL7PkY9dPLCRParIyzb9aPmf~MntNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABHNqwgkhJnBW4ymaRsdVmITAha-ff0UiALfKSlznqp5HcSewgMHbzQ0I01TQytFnW\n")
sys.stdout.write(sess.recv(1000))

sock =  socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sock.connect(("127.0.0.1",7656));
sock.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sock.recv(1000))
sock.send("STREAM CONNECT"+name+" DESTINATION=http://amiga.i2p"+silent+"\n")

# wait for acknowledgement before sending data, if we asked for it
if (silent==" SILENT=false") :
	sys.stdout.write(sock.recv(1000))

while (1) :
    buf=sock.recv(1000)
    sys.stdout.write(buf)
    if not buf : break

print


