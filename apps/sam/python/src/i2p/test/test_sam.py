
# -----------------------------------------------------
# test_sam.py: Unit tests for sam.py.
# -----------------------------------------------------

# Make sure we can import i2p
import sys; sys.path += ['../../']

import traceback, time, thread, threading, random, copy
from i2p import eep, sam

def test_passed(s, msg='OK'):
  """Notify user that the given unit test passed."""
  print '  ' + (s + ':').ljust(50) + msg

def verify_html(s):
  """Raise an error if s does not end with </html>"""
  assert s.strip().lower()[-7:] == '</html>'

def stream_client(dest):
  """Sub-unit test for sam.socket in SOCK_STREAM mode."""
  S = sam.socket('Alice', sam.SOCK_STREAM)
  S.connect(dest)
  S.send('GET / HTTP/1.0\r\n\r\n')          # Send request
  f = S.makefile()                          # File object

  while True:                               # Read header
    line = f.readline().strip()             # Read a line
    if line == '': break                    # Content begins

  s = f.read()                              # Get content
  f.close()
  S.close()

def stream_client_test():
  """Unit test for sam.socket in SOCK_STREAM mode."""
  url = 'morph.i2p'
  stream_client('http://' + url + '/')
  stream_client(url)
  stream_client(url + '/')
  stream_client('http://' + url)
  stream_client(sam.resolve('http://' + url + '/'))
  test_passed('sam.socket stream client')

def packet_test(raw=True):
  """Unit test for sam.socket in SOCK_DGRAM or SOCK_RAW modes."""

  try:
    multithread_wait_time = 500.0
    may_need_increase = False

    if raw:
      C = sam.socket('Carola', sam.SOCK_RAW, in_depth=0, out_depth=0)
      D = sam.socket('Davey', sam.SOCK_RAW, in_depth=0, out_depth=0)
    else:
      C = sam.socket('Carol', sam.SOCK_DGRAM,in_depth=0,out_depth=0)
      D = sam.socket('Dave', sam.SOCK_DGRAM, in_depth=0, out_depth=0)

    global C_recv, D_recv, C_got, D_got, __lock
    C_recv = []          # Packets C *should* receive
    D_recv = []          # Packets D *should* receive
    C_got  = []          # Packets C actually got
    D_got  = []          # Packets D actually got

    n = 50               # Create n threads
    m = 40               # Each thread sends m packets

    global __done_count
    __done_count = 0
    __lock = threading.Lock()

    # Use C and D to send and read in many different threads.
    def f():
      # This code is run in each separate thread
      global C_recv, D_recv, C_got, D_got, __lock, __done_count
      for i in range(m):
        # Random binary string of length 2-80.
        index_list = range(random.randrange(2, 80))
        s = ''.join([chr(random.randrange(256)) for j in index_list])
        if random.randrange(2) == 0:
          # Send packet from C to D, and log it.
          C.sendto(s, 0, D.dest)
          __lock.acquire()
          D_recv += [s]
          __lock.release()
        else:
          # Send packet from D to C, and log it.
          D.sendto(s, 0, C.dest)
          __lock.acquire()
          C_recv += [s]
          __lock.release()
        time.sleep(0.01*random.uniform(0.0,1.0))
        # Read any available packets.
        try: (p, fromaddr) = C.recvfrom(1000, sam.MSG_DONTWAIT)
        except sam.BlockError: p = None
        if p != None and not raw: assert fromaddr == D.dest 

        __lock.acquire()
        if p != None: C_got += [p]
        __lock.release()

        try: (p, fromaddr) = D.recvfrom(1000, sam.MSG_DONTWAIT)
        except sam.BlockError: p = None
        if p != None and not raw: assert fromaddr == C.dest 

        __lock.acquire()
        if p != None: D_got += [p]
        __lock.release()

      __lock.acquire()
      __done_count += 1
      __lock.release()

    # Create n threads.
    for i in range(n):
      threading.Thread(target=f).start()

    # Wait for them to finish.
    while __done_count < n: time.sleep(0.01)

    # Read any left-over received packets.
    end_time = time.clock() + multithread_wait_time
    while time.clock() < end_time:
      # Read any available packets.
      try: (p, fromaddr) = C.recvfrom(1000, sam.MSG_DONTWAIT)
      except sam.BlockError: p = None
      if p != None and not raw: assert fromaddr == D.dest 

      if p != None: C_got += [p]

      try: (p, fromaddr) = D.recvfrom(1000, sam.MSG_DONTWAIT)
      except sam.BlockError: p = None
      if p != None and not raw: assert fromaddr == C.dest 

      if p != None: D_got += [p]
      if len(C_got) == len(C_recv) and len(D_got) == len(D_recv):
        break

    if time.clock() >= end_time:
      may_need_increase = True

    C_got.sort()
    D_got.sort()
    C_recv.sort()
    D_recv.sort()

    assert C_got == C_recv
    assert D_got == D_recv

    C.close()
    D.close()
  except:
    if raw:
      print 'Unit test failed for sam.socket (SOCK_RAW).'
      print 'Raw packets are not reliable.'
    else:
      print 'Unit test failed for sam.socket (SOCK_DGRAM).'
      print 'Datagram packets are not reliable.'

    if may_need_increase:
      print 'Try increasing multithread_wait_time.'

    traceback.print_exc(); sys.exit()

  if raw:
    test_passed('sam.socket (SOCK_RAW)')
  else:
    test_passed('sam.socket (SOCK_RAW)')

def stream_test():
  """Multithreaded unit test for sam.socket (SOCK_STREAM)."""

  try:
    multithread_wait_time = 200.0
    may_need_increase = False

    kwargs = {'in_depth':0, 'out_depth':0}
    C = sam.socket('Carolic', sam.SOCK_STREAM, **kwargs)
    D = sam.socket('David', sam.SOCK_STREAM, **kwargs)
    Cout = sam.socket('Carolic', sam.SOCK_STREAM, **kwargs)
    Dout = sam.socket('David', sam.SOCK_STREAM, **kwargs)

    assert C.dest == Cout.dest
    assert D.dest == Dout.dest

    C.listen(5)
    D.listen(5)
    Cout.connect(D.dest)
    Dout.connect(C.dest)
    (Cin, ignoredest) = C.accept()
    (Din, ignoredest) = D.accept()

    global C_recv, D_recv, C_got, D_got, __lock
    C_recv = []          # String data C *should* receive
    D_recv = []          # String data D *should* receive
    C_got  = []          # String data C actually got
    D_got  = []          # String data D actually got

    n = 50               # Create n threads
    m = 40               # Each thread sends m strings

    global __done_count
    __done_count = 0
    __lock = threading.Lock()

    # Use C and D to send and read in many different threads.
    def f():
      # This code is run in each separate thread
      global C_recv, D_recv, C_got, D_got, __lock, __done_count
      for i in range(m):
        # Random binary string of length 2-80.
        index_list = range(random.randrange(2, 80))
        s = ''.join([chr(random.randrange(256)) for j in index_list])
        if random.randrange(2) == 0:
          # Send packet from C to D, and log it.
          __lock.acquire()
          Cout.send(s)
          D_recv += [s]
          __lock.release()
        else:
          # Send packet from D to C, and log it.
          __lock.acquire()
          Dout.send(s)
          C_recv += [s]
          __lock.release()
        time.sleep(0.01*random.uniform(0.0,1.0))
        # Read any available string data, non-blocking.

        __lock.acquire()
        try: p = Cin.recv(100000, sam.MSG_DONTWAIT)
        except sam.BlockError: p = None
        if p != None: C_got += [p]
        __lock.release()

        __lock.acquire()
        try: p = Din.recv(100000, sam.MSG_DONTWAIT)
        except sam.BlockError: p = None
        if p != None: D_got += [p]
        __lock.release()

      __lock.acquire()
      __done_count += 1
      __lock.release()

    # Create n threads.
    for i in range(n):
      threading.Thread(target=f).start()

    # Wait for them to finish.
    while __done_count < n: time.sleep(0.01)

    # Read any left-over received string data.
    end_time = time.clock() + multithread_wait_time
    while time.clock() < end_time:
      # Read any available string data, non-blocking.
      try: p = Cin.recv(100000, sam.MSG_DONTWAIT)
      except sam.BlockError: p = None
      if p != None: C_got += [p]

      try: p = Din.recv(100000, sam.MSG_DONTWAIT)
      except sam.BlockError: p = None
      if p != None: D_got += [p]

      if len(''.join(C_got)) == len(''.join(C_recv)) and \
         len(''.join(D_got)) == len(''.join(D_recv)):
        break

    if time.clock() >= end_time:
      may_need_increase = True

    C_got = ''.join(C_got)
    D_got = ''.join(D_got)
    C_recv = ''.join(C_recv)
    D_recv = ''.join(D_recv)
    assert C_got == C_recv
    assert D_got == D_recv

    Cin.close()
    Din.close()
    Cout.close()
    Dout.close()
    C.close()
    D.close()
  except:
    print 'Unit test failed for sam.socket ' + \
          '(SOCK_STREAM, multithreaded).'

    if may_need_increase:
      print 'Try increasing multithread_wait_time.'

    traceback.print_exc(); sys.exit()

  test_passed('sam.socket (SOCK_STREAM, multithreaded)')


def noblock_stream_test():
  """Unit test for non-blocking stream commands and listen."""

  serv = sam.socket('Allison',sam.SOCK_STREAM,in_depth=0,out_depth=0)
  serv.setblocking(False)
  serv.listen(100)
  assert serv.gettimeout() == 0.0

  msg_to_client = 'Hi, client!!!!'
  msg_to_server = 'Hi, server!'

  nconnects = 5

  global server_done, client_count, client_lock
  server_done = False
  client_count = 0
  client_lock = threading.Lock()

  def serv_func(n = nconnects):
    while True:
      try:
        (C, ignoredest) = serv.accept()
        C.send(msg_to_client)
        rmsg = C.recv(len(msg_to_server), sam.MSG_WAITALL)
        if rmsg != msg_to_server:
          raise ValueError('message should have been: ' +
            repr(msg_to_server) + ' was: ' + repr(rmsg))
        C.close()
        n -= 1
        if n == 0: break
      except sam.BlockError:
        pass
      time.sleep(0.01)
    global server_done
    server_done = True
    
  def client_func():
    # FIXME: i2p.sam.NetworkError('TIMEOUT', '') errors are produced
    # for our streams if we use '' for all clients.  Why?
    C = sam.socket('Bobb', sam.SOCK_STREAM, in_depth=0, out_depth=0)
    C.setblocking(False)
    try:
      C.connect(serv.dest)
    except sam.BlockError:
      # One could also use timeout=0.1 and loop
      (Rlist, Wlist, Elist) = sam.select([C], [C], [C])
      if len(Elist) > 0:
        assert Elist[0] == C
        raise Elist[0].sessobj.err
    C.send(msg_to_server)
    C.setblocking(True)
    rmsg = C.recv(len(msg_to_client), sam.MSG_WAITALL)
    if rmsg != msg_to_client:
      raise ValueError('message should have been: ' +
        repr(msg_to_client) + ' was: ' + repr(rmsg))
    C.close()
    global client_count, client_lock

    # Synchronized
    client_lock.acquire()
    try: client_count += 1
    finally: client_lock.release()


  thread.start_new_thread(serv_func, ())

  for i in range(nconnects):
    thread.start_new_thread(client_func, ())

  while True:
    if server_done and client_count == nconnects: break
    time.sleep(0.01)

  test_passed('sam.listen (SOCK_STREAM), and non-blocking IO')

def tunnel_server_demo():
  """Demo for TunnelServer."""

  T = sam.TunnelServer('Alisick', 8080, in_depth=0, out_depth=0)

  print 'Server ready at:'
  print T.dest
  while True:
    time.sleep(0.01)

def tunnel_client_demo():
  """Demo for TunnelClient."""

  T = sam.TunnelClient('Alliaha', 8001, 'duck.i2p', \
                       in_depth=0, out_depth=0)

  print 'Serving up duck.i2p at http://127.0.0.1:8001/'
  while True:
    time.sleep(0.01)



# select, poll
# tunnel_client, tunnel_server
# noblocking unit tests

def multi_stream_test(n):
  """See if we can have n streams open at once."""
  server = None
  client = [None] * n

  server = sam.socket('Aligi',sam.SOCK_STREAM,in_depth=0,out_depth=0)
  server.listen(n)
  
  for i in range(n):
    client[i] = sam.socket('Bobo', sam.SOCK_STREAM, \
                           in_depth=0, out_depth=0)

  for i in range(n):
    client[i].connect(server.dest)
    client[i].send('Hi')

  for i in range(n):
    client[i].close()
  server.close()

  test_passed(str(n) + ' streams open at once')



# Todo: Write unit tests for TunnelServer, TunnelClient.

def test():
  print 'Testing:'
  print "Comment and uncomment tests manually, if they don't finish."

#  noblock_stream_test()
#  stream_client_test()
#  packet_test(raw=True)
#  stream_test()
#  multi_stream_test(200)

# Demos (manual unit tests):
#  tunnel_server_demo()
#  tunnel_client_demo()           # This fails too

# Note: The datagram unit test fails, apparently due to a bug in I2P
# (packet loss).
##  packet_test(raw=False)

if __name__ == '__main__':
  test()
