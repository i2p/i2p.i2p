
# -----------------------------------------------
# stream_noblock.py: Non-blocking stream server
# -----------------------------------------------

import i2p
from i2p import sam
import thread, time

S = sam.socket('Dave', sam.SOCK_STREAM)
S.listen(10)                      # Queue up to 10 connections
S.setblocking(False)              # Non-blocking
print 'Serving at:', S.dest

def handle_connection(C):
  """Handle a single connection in a thread of its own."""
  try:
    f = C.makefile()              # File object
    req = f.readline()            # Read HTTP request

    s = '<h1>Hello!</h1>'         # String to send back

    f.write('HTTP/1.0 200 OK\r\nContent-Type: text/html' +
    '\r\nContent-Length: ' + str(int(len(s))) + '\r\n\r\n' + s)

    f.close()                     # Close file
    C.close()                     # Close connection
  except sam.Error, e:
    # Recover from SAM errors
    print 'Warning:', str(e)

while True:
  try:
    (C, remotedest) = S.accept()  # Get a connection
    thread.start_new_thread(handle_connection, (C,))
  except sam.BlockError:
    # Ignore 'command would have blocked' errors
    pass
  time.sleep(0.01)                # Reduce CPU usage