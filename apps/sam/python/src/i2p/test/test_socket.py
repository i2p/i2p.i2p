
# --------------------------------------------------------
# test_socket.py: Unit tests for socket, select.
# --------------------------------------------------------

# Make sure we can import i2p
import sys; sys.path += ['../../']

import traceback, time, thread, threading, random, copy
from i2p import socket, select

def test_passed(s, msg='OK'):
  """Notify user that the given unit test passed."""
  print '  ' + (s + ':').ljust(50) + msg

def verify_html(s):
  """Raise an error if s does not end with </html>"""
  assert s.strip().lower()[-7:] == '</html>'

def resolve_test(name='duck.i2p'):
  """Unit test for resolve."""
  try:
    rname = socket.resolve(name)
  except:
    print 'Unit test failed for socket.resolve'
    traceback.print_exc(); sys.exit()

  test_passed('socket.resolve', 'See below')
  print '  Use hosts.txt to verify that ' + name + '=' +   \
        rname[:15] + '...'

def stream_client(dest):
  """Sub-unit test for socket.socket in SOCK_STREAM mode."""
  S = socket.socket('Alice', socket.SOCK_STREAM)
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
  """Unit test for socket.socket in SOCK_STREAM mode."""
  url = 'duck.i2p'
  stream_client('http://' + url + '/')
  stream_client(url)
  stream_client(url + '/')
  stream_client('http://' + url)
  stream_client(socket.resolve('http://' + url + '/'))
  test_passed('socket.socket stream client')

def packet_test(raw=True):
  """Unit test for socket.socket in SOCK_DGRAM or SOCK_RAW modes."""

  try:
    multithread_wait_time = 500.0
    may_need_increase = False

    kwargs = {'in_depth': 0, 'out_depth': 0}
    if raw:
      C = socket.socket('Carola', socket.SOCK_RAW, **kwargs)
      D = socket.socket('Davey', socket.SOCK_RAW, **kwargs)
    else:
      C = socket.socket('Carol', socket.SOCK_DGRAM, **kwargs)
      D = socket.socket('Dave', socket.SOCK_DGRAM, **kwargs)

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
        try: (p, fromaddr) = C.recvfrom(1000, socket.MSG_DONTWAIT)
        except socket.BlockError: p = None
        if p != None and not raw: assert fromaddr == D.dest 

        __lock.acquire()
        if p != None: C_got += [p]
        __lock.release()

        try: (p, fromaddr) = D.recvfrom(1000, socket.MSG_DONTWAIT)
        except socket.BlockError: p = None
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
    end_time = time.time() + multithread_wait_time
    while time.time() < end_time:
      # Read any available packets.
      try: (p, fromaddr) = C.recvfrom(1000, socket.MSG_DONTWAIT)
      except socket.BlockError: p = None
      if p != None and not raw: assert fromaddr == D.dest 

      if p != None: C_got += [p]

      try: (p, fromaddr) = D.recvfrom(1000, socket.MSG_DONTWAIT)
      except socket.BlockError: p = None
      if p != None and not raw: assert fromaddr == C.dest 

      if p != None: D_got += [p]
      if len(C_got) == len(C_recv) and len(D_got) == len(D_recv):
        break

    if time.time() >= end_time:
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
      print 'Unit test failed for socket.socket (SOCK_RAW).'
      print 'Raw packets are not reliable.'
    else:
      print 'Unit test failed for socket.socket (SOCK_DGRAM).'
      print 'Datagram packets are not reliable.'

    if may_need_increase:
      print 'Try increasing multithread_wait_time.'

    traceback.print_exc(); sys.exit()

  if raw:
    test_passed('socket.socket (SOCK_RAW)')
  else:
    test_passed('socket.socket (SOCK_DGRAM)')

def stream_test():
  """Multithreaded unit test for socket.socket (SOCK_STREAM)."""

  try:
    multithread_wait_time = 200.0
    may_need_increase = False

    kwargs = {'in_depth':0, 'out_depth':0}
    C = socket.socket('Carolic', socket.SOCK_STREAM, **kwargs)
    D = socket.socket('David', socket.SOCK_STREAM, **kwargs)
    Cout = socket.socket('Carolic', socket.SOCK_STREAM, **kwargs)
    Dout = socket.socket('David', socket.SOCK_STREAM, **kwargs)

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
        try: p = Cin.recv(100000, socket.MSG_DONTWAIT)
        except socket.BlockError: p = None
        if p != None: C_got += [p]
        __lock.release()

        __lock.acquire()
        try: p = Din.recv(100000, socket.MSG_DONTWAIT)
        except socket.BlockError: p = None
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
    end_time = time.time() + multithread_wait_time
    while time.time() < end_time:
      # Read any available string data, non-blocking.
      try: p = Cin.recv(100000, socket.MSG_DONTWAIT)
      except socket.BlockError: p = None
      if p != None: C_got += [p]

      try: p = Din.recv(100000, socket.MSG_DONTWAIT)
      except socket.BlockError: p = None
      if p != None: D_got += [p]

      if len(''.join(C_got)) == len(''.join(C_recv)) and \
         len(''.join(D_got)) == len(''.join(D_recv)):
        break

    if time.time() >= end_time:
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
    print 'Unit test failed for socket.socket ' + \
          '(SOCK_STREAM, multithreaded).'

    if may_need_increase:
      print 'Try increasing multithread_wait_time.'

    traceback.print_exc(); sys.exit()

  test_passed('socket.socket (SOCK_STREAM, multithreaded)')


def noblock_stream_test():
  """Unit test for non-blocking stream commands and listen."""

  kwargs = {'in_depth': 0, 'out_depth': 0}
  serv = socket.socket('Allison',socket.SOCK_STREAM,**kwargs)
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
        rmsg = C.recv(len(msg_to_server), socket.MSG_WAITALL)
        if rmsg != msg_to_server:
          raise ValueError('message should have been: ' +
            repr(msg_to_server) + ' was: ' + repr(rmsg))
        C.close()
        n -= 1
        if n == 0: break
      except socket.BlockError:
        pass
      time.sleep(0.01)
    global server_done
    server_done = True
    
  def client_func():
    # FIXME: i2p.socket.NetworkError('TIMEOUT', '') errors are produced
    # for our streams if we use '' for all clients.  Why?
    C = socket.socket('Bobb', socket.SOCK_STREAM, **kwargs)
    C.setblocking(False)
    try:
      C.connect(serv.dest)
    except socket.BlockError:
      # One could also use timeout=0.1 and loop
      (Rlist, Wlist, Elist) = select.select([C], [C], [C])
      if len(Elist) > 0:
        assert Elist[0] == C
        raise Elist[0].sessobj.err
    C.send(msg_to_server)
    C.setblocking(True)
    rmsg = C.recv(len(msg_to_client), socket.MSG_WAITALL)
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

  test_passed('socket.listen (SOCK_STREAM), and non-blocking IO')

def multi_stream_test(n):
  """See if we can have n streams open at once."""
  server = None
  client = [None] * n

  kwargs = {'in_depth': 0, 'out_depth': 0}
  server = socket.socket('Aligi',socket.SOCK_STREAM,**kwargs)
  server.listen(n)
  
  for i in range(n):
    client[i] = socket.socket('Bobo', socket.SOCK_STREAM, \
                           in_depth=0, out_depth=0)

  for i in range(n):
    client[i].connect(server.dest)
    client[i].send('Hi')

  for i in range(n):
    client[i].close()
  server.close()

  test_passed(str(n) + ' streams open at once')


# Todo:
# select, poll
# More nonblocking unit tests


def test():
  print 'Testing:'
  print "Comment and uncomment tests manually, if they don't finish."

  resolve_test()
  noblock_stream_test()
  stream_client_test()
  packet_test(raw=True)
  packet_test(raw=False)
  stream_test()
  multi_stream_test(200)

if __name__ == '__main__':
  test()
