#!/usr/bin/python


# create an stream session
# then an "accept" stream connected to this session
# then another "accept" stream from the same session
# then listen from the first stream and then listen from the second
# usage : ./samIn.py [ silent [ name ] ]
#            name : the session id ( defaults to InTest )
#            silent : true or false : tells wether we want to receive the incoming stream destination
#                       as first line

import socket
import sys
import time

if len(sys.argv)>=2 :
	silent = " SILENT="+sys.argv[1]
else :  silent = " SILENT=false"

if len(sys.argv)>=3 :
	name = sys.argv[2]
else :  name = "inTest"




sess = socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sess.connect(("127.0.0.1",7656));
sess.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sess.recv(1000))
sess.send("SESSION CREATE STYLE=STREAM ID="+name+" DESTINATION=tYhjbFlFL38WFuO5eCzTvE0UBr4RfaqWMKlekGeMoB-Ouz7nYaWfiS-9j3jMiZT7FH~pwdmoSREOs2ZbXK84sR59P~pPfeCMxnJrk57f3U9uKzXkesjkKWYco3YAGs-G8sw8Fu2FBx0Do57yBdA9~j8Zq6pMjmgPBXCLuXG3vo0Z8zUWCjApJyFY6OXYopHck9Fz9vKy7YhC6zXFHfEuNHVkAooduiLd~aCoGij0TW3lH2rTVU-lx-DUdi6edxQ5-RvDNkXfikvytoCpRkivbNVytjCJLk~7RNU4FpBD20wTZWNJmEG3OY3cjNjawJVFdNjtgczh9K7gZ7ad-NjVjZVhXEj1lU8mk~vAH-2QE5om8dstWUwWoNDwmVDlvIJNKzQmahG~VrpFexFHXO0n3fKIXcSgWGOHDExM8w9neCt7AxUjxPDtXXuYNW~bRwcfiL-C9~z4K9rmwiTPZX0lmsToSXTF28l7WAoj~TMT9kZAjQeFRRWU5oW5oxVuonVvAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABngJSS8xMyF4t82otZmCDhrKjbm-QLMtOLoumwR28ebDHEd4clF6O7aRa3d3yRH7p\n")
sys.stdout.write(sess.recv(1000))





def accept() :
	sock = socket.socket(
		socket.AF_INET, socket.SOCK_STREAM)
	sock.connect(("127.0.0.1",7656));
	sock.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
	sys.stdout.write(sock.recv(1000))
	sock.send("STREAM ACCEPT ID=" + name + silent+"\n")
	print "STREAM ACCEPT ID="+name+silent
	if (silent==" SILENT=false") :
		sys.stdout.write( sock.recv(100) )
	return sock

def echo( sock, lines ) :
	l = 0
	while lines==-1 or l<lines :
	    chunk = sock.recv(1000)
	    if lines!=-1 : l = l + 1
	    if not chunk : break
	    print chunk
	    sock.send(chunk)
	print



sock1 = accept()
time.sleep(1)

sock2 = accept()

print "Second listening session"
try :
	echo(sock2, -1)
except :
	print sock2

if silent == " SILENT=false" :
	sys.stdout.write(sock1.recv(1000))
else :
	# we know sock1 is accepted if it receives a byte
	sock1.recv(1)

sock3 = accept()


print "First listening session"
echo(sock1, 2)
sock1.close()

print "Third listening session"
echo(sock3, -1)


