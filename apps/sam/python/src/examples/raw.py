
# -----------------------------------------------
# raw.py: Raw client
# -----------------------------------------------

from i2p import sam

dest = sam.resolve('yourserver.i2p')      # Send to dest
S = sam.socket('Carol', sam.SOCK_RAW)
S.sendto('Hello packet', 0, dest)
