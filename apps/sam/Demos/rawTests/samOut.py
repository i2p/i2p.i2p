#!/usr/bin/python

# sends a message to datagram destinations opened by samForward.py and samIn.py, using specified sending session name
# at least samForward.py should be running for results to be seen
# usage : ./samOut.py [ sendingSessionName [ message ... ] ]
#             sendingSessionName : default = datagramSamForward
#             message : default = "this is nice message"

import socket
import sys
import time

if len(sys.argv)>=2 :
	name = sys.argv[1]
else :
	name = "rawSamForward"

if len(sys.argv)>2 :
	message = ''.join([s+' ' for s in sys.argv[2:]]).strip()
else :
	message = "This is a nice message"


# client.py
port = 7655
host = "localhost"
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.bind(("", 0))
s.sendto("3.0 "+name+" tYhjbFlFL38WFuO5eCzTvE0UBr4RfaqWMKlekGeMoB-Ouz7nYaWfiS-9j3jMiZT7FH~pwdmoSREOs2ZbXK84sR59P~pPfeCMxnJrk57f3U9uKzXkesjkKWYco3YAGs-G8sw8Fu2FBx0Do57yBdA9~j8Zq6pMjmgPBXCLuXG3vo0Z8zUWCjApJyFY6OXYopHck9Fz9vKy7YhC6zXFHfEuNHVkAooduiLd~aCoGij0TW3lH2rTVU-lx-DUdi6edxQ5-RvDNkXfikvytoCpRkivbNVytjCJLk~7RNU4FpBD20wTZWNJmEG3OY3cjNjawJVFdNjtgczh9K7gZ7ad-NjVjZVhXEj1lU8mk~vAH-2QE5om8dstWUwWoNDwmVDlvIJNKzQmahG~VrpFexFHXO0n3fKIXcSgWGOHDExM8w9neCt7AxUjxPDtXXuYNW~bRwcfiL-C9~z4K9rmwiTPZX0lmsToSXTF28l7WAoj~TMT9kZAjQeFRRWU5oW5oxVuonVvAAAA\n"+message, (host, port))
s.sendto("3.0 "+name+" EYUpJFeW9tiubXR0aOjvCJ~ndj3xN0Wn-ljuGdbpOEttPg7nj0VCTOQDJ~FAolzn9FIDdmR3VjM0OFFDT46Q5HN4vShXFE2VNC8e3~GjzxJfaJhijRC2R9oIOzsNlzKtInD2o9lh0PxPioNMCigwmgWuqlQHs4tjWeaYRAtooHxbrtuoCIhIdGfyVV-nAcPiyYbouKq3leETXE~4kBXm-LfWfyPtrv6OuDk3GBVVcthv19GYBmnl2YI8HpJjc-G-TvNkgYishjzIJyEW-Xrpy43R4ZBXlyQqnheGLlbOEY8NLDbyNHLRMMOGbcr~67SVE3Iw3RqQ3Dhrkq2FCaQwcDucfIUCCbOfCZgu0hlnCkS42xsUvegQeiwMxbdI~h9v7vcR3yFFOrHX6WQvIZSbFLKNGArGJcfmOJVLqw1wTC4AgYXjk3csVDPd-QWbMXOuodyBgrg27Ds2BBYTsVXWskoo6ASsMIQZ6jMfL7PkY9dPLCRParIyzb9aPmf~MntNAAAA\n"+message, (host, port))

