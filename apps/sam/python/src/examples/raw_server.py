
# -----------------------------------------------
# raw_server.py: Raw server
# -----------------------------------------------

from i2p import sam

S = sam.socket('Eve', sam.SOCK_RAW)
print 'Serving at:', S.dest

while True:
  data = S.recv(1000)            # Read packet
  print 'Got', data
