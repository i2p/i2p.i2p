
# ---------------------------------------------------
# datagram_noblock.py: Non-blocking datagram server
# ---------------------------------------------------

from i2p import sam
import time

S = sam.socket('Eve', sam.SOCK_DGRAM)
print 'Serving at:', S.dest
S.setblocking(False)

while True:
  try:
    (data, dest) = S.recvfrom(1000)    # Read packet
    print 'Got', data, 'from', dest
    S.sendto('Hi client!', 0, dest)
  except sam.BlockError:               # No data available
    pass
  time.sleep(0.01)                     # Reduce CPU usage