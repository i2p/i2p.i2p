#!/usr/bin/python


# create a SAM datagram session that writes incoming messages on its master session stream
# and a listen forever
# usage : ./samIn.py [session name]

import socket
import sys

if len(sys.argv)==2 :
	name = sys.argv[1]
else :
	name = "rawSamIn"


sess = socket.socket(
    socket.AF_INET, socket.SOCK_STREAM)
sess.connect(("127.0.0.1",7656));
sess.send("HELLO VERSION MIN=3.0 MAX=3.0\n")
sys.stdout.write(sess.recv(1000))
sess.send("SESSION CREATE STYLE=RAW ID="+name+" DESTINATION=tYhjbFlFL38WFuO5eCzTvE0UBr4RfaqWMKlekGeMoB-Ouz7nYaWfiS-9j3jMiZT7FH~pwdmoSREOs2ZbXK84sR59P~pPfeCMxnJrk57f3U9uKzXkesjkKWYco3YAGs-G8sw8Fu2FBx0Do57yBdA9~j8Zq6pMjmgPBXCLuXG3vo0Z8zUWCjApJyFY6OXYopHck9Fz9vKy7YhC6zXFHfEuNHVkAooduiLd~aCoGij0TW3lH2rTVU-lx-DUdi6edxQ5-RvDNkXfikvytoCpRkivbNVytjCJLk~7RNU4FpBD20wTZWNJmEG3OY3cjNjawJVFdNjtgczh9K7gZ7ad-NjVjZVhXEj1lU8mk~vAH-2QE5om8dstWUwWoNDwmVDlvIJNKzQmahG~VrpFexFHXO0n3fKIXcSgWGOHDExM8w9neCt7AxUjxPDtXXuYNW~bRwcfiL-C9~z4K9rmwiTPZX0lmsToSXTF28l7WAoj~TMT9kZAjQeFRRWU5oW5oxVuonVvAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABngJSS8xMyF4t82otZmCDhrKjbm-QLMtOLoumwR28ebDHEd4clF6O7aRa3d3yRH7p\n")
sys.stdout.write(sess.recv(1000))

# listen incoming messages
while 1 :
    chunk = sess.recv(10000)
    sys.stdout.write(chunk+'\n')
    if not chunk : break
print

