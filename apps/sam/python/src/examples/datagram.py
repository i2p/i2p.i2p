
# -----------------------------------------------
# datagram.py: Datagram client
# -----------------------------------------------

from i2p import sam

dest = sam.resolve('yourserver.i2p')
S = sam.socket('Bob', sam.SOCK_DGRAM)
S.sendto('Hello packet', 0, dest)

# Get packet up to 1000 bytes -- the rest is discarded.
(data, dest) = S.recvfrom(1000)

print data
