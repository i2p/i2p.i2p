
# ---------------------------------------------------
# raw_noblock.py: Non-blocking raw server
# ---------------------------------------------------

from i2p import sam
import time

S = sam.socket('Eve', sam.SOCK_RAW)
print 'Serving at:', S.dest
S.setblocking(False)

while True:
  try:
    data = S.recv(1000)                # Read packet
    print 'Got', data
  except sam.BlockError:               # No data available
    pass
  time.sleep(0.01)                     # Reduce CPU usage