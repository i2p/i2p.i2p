
# --------------------------------------------------------
# test_tunnel.py: Demos for tunnel (unit tests needed).
# --------------------------------------------------------

# Make sure we can import i2p
import sys; sys.path += ['../../']

import time
from i2p import tunnel

def tunnel_server_demo():
  """Demo for tunnel.TunnelServer."""

  T = tunnel.TunnelServer('Alisick', 8080, in_depth=0, out_depth=0)

  print 'Server ready at:'
  print T.dest
  while True:
    time.sleep(0.01)

def tunnel_client_demo():
  """Demo for tunnel.TunnelClient."""

  T = tunnel.TunnelClient('Alliaha', 8001, 'duck.i2p', \
                       in_depth=0, out_depth=0)

  print 'Serving up duck.i2p at http://127.0.0.1:8001/'
  while True:
    time.sleep(0.01)



def test():
  print 'Demo:'

# Demos:
#  tunnel_server_demo()
  tunnel_client_demo()

if __name__ == '__main__':
  test()
