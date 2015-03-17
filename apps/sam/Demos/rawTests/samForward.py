#!/usr/bin/python

import socket
import sys

# create a forward style SAM raw datagram session
# that forwards messages on specified port (default port : 25000)
# creates a standard datagram server that listens on this port forever
# usage : ./samForward.py [port [SAM session name]]

if len(sys.argv)>=2 :
	port = eval(sys.argv[1])
else :
	port = 25000

if len(sys.argv)==3 :
	name = sys.argv[2]
else :
	name = "rawSamForward"

sess = socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sess.connect(("127.0.0.1",7656));
sess.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sess.recv(1000))
sess.send("SESSION CREATE STYLE=RAW PORT="+str(port)+" ID="+name+" DESTINATION=EYUpJFeW9tiubXR0aOjvCJ~ndj3xN0Wn-ljuGdbpOEttPg7nj0VCTOQDJ~FAolzn9FIDdmR3VjM0OFFDT46Q5HN4vShXFE2VNC8e3~GjzxJfaJhijRC2R9oIOzsNlzKtInD2o9lh0PxPioNMCigwmgWuqlQHs4tjWeaYRAtooHxbrtuoCIhIdGfyVV-nAcPiyYbouKq3leETXE~4kBXm-LfWfyPtrv6OuDk3GBVVcthv19GYBmnl2YI8HpJjc-G-TvNkgYishjzIJyEW-Xrpy43R4ZBXlyQqnheGLlbOEY8NLDbyNHLRMMOGbcr~67SVE3Iw3RqQ3Dhrkq2FCaQwcDucfIUCCbOfCZgu0hlnCkS42xsUvegQeiwMxbdI~h9v7vcR3yFFOrHX6WQvIZSbFLKNGArGJcfmOJVLqw1wTC4AgYXjk3csVDPd-QWbMXOuodyBgrg27Ds2BBYTsVXWskoo6ASsMIQZ6jMfL7PkY9dPLCRParIyzb9aPmf~MntNAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABHNqwgkhJnBW4ymaRsdVmITAha-ff0UiALfKSlznqp5HcSewgMHbzQ0I01TQytFnW\n")
sys.stdout.write(sess.recv(10000))

# listening server
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("", port))
print "waiting on port:", port
while 1:
    data, addr = s.recvfrom(40000)
    print data, " received from ", addr, "length=", len(data)

