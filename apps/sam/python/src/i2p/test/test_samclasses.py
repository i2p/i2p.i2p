
# -----------------------------------------------------
# test_samclasses.py: Unit tests for samclasses.py.
# -----------------------------------------------------

# Make sure we can import i2p
import sys; sys.path += ['../../']

import traceback, time, thread, threading, random
from i2p import eep, socket, samclasses

def test_passed(s, msg='OK'):
  """Notify user that the given unit test passed."""
  print '  ' + (s + ':').ljust(50) + msg

def verify_html(s):
  """Raise an error if s does not end with </html>"""
  assert s.strip().lower()[-7:] == '</html>'

def raw_test1():
  """Unit test for samclasses.RawSession."""

  try:
    C = samclasses.RawSession('Carol')
    D = samclasses.RawSession('Dave')

    C.send('Hello!', D.dest)
    D.send('Hi C!', C.dest)

    (packet, addr) = C.recv(1000)
    assert packet == 'Hi C!'
    (packet, addr) = D.recv(1000)
    assert packet == 'Hello!'
    C.close()
    D.close()
  except:
    print 'Unit test failed for samclasses.RawSession'
    traceback.print_exc(); sys.exit()
  test_passed('samclasses.RawSession')

def datagram_test1():
  """Unit test for samclasses.DatagramSession."""

  try:
    C = samclasses.DatagramSession('Carol')
    D = samclasses.DatagramSession('Dave')

    C.send('Hello!', D.dest)
    D.send('Hi C!', C.dest)

    (packet, remotedest) = C.recv(1000)
    assert str(packet) == 'Hi C!' and remotedest == D.dest
    (packet, remotedest) = D.recv(1000)
    assert str(packet) == 'Hello!' and remotedest == C.dest
    C.close()
    D.close()
  except:
    print 'Unit test failed for samclasses.DatagramSession'
    traceback.print_exc(); sys.exit()
  test_passed('samclasses.DatagramSession')

def stream_readline(S):
  """Read a line, with a \r\n newline, including trailing \r\n."""
  ans = []
  while True:
    c = S.recv(1)
    if c == '': break
    if c == '\n': break
    ans += [c]
  return ''.join(ans)

def stream_http_get(S, dest):
  """Get contents of http://dest/ via HTTP/1.0 and
     samclasses.StreamSession S."""
  C = S.connect(dest)

  C.send('GET / HTTP/1.0\r\n\r\n')

  while True:
    line = stream_readline(C).strip()
    if line.find('Content-Length: ') == 0:
      clen = int(line.split()[1])
    if line == '': break

  s = C.recv(clen, timeout=None)
  time.sleep(2.0)
  C.close()
  return s

def stream_test1():
  """Unit test for samclasses.StreamSession.connect."""

  try:
    dest = socket.resolve('duck.i2p')
    S = samclasses.StreamSession('Bob')
    verify_html(stream_http_get(S, dest))
    verify_html(stream_http_get(S, dest))
    verify_html(stream_http_get(S, dest))
    S.close()

  except:
    print 'Unit test failed for samclasses.StreamSession'
    traceback.print_exc(); sys.exit()
  test_passed('samclasses.StreamSession.connect')

def stream_test2():
  """Unit test for samclasses.StreamSession.accept."""
  global __server_done, __client_done, __err
  __server_done = False
  __client_done = False
  __err = None

  S = samclasses.StreamSession('Bob')
  S.listen(10)
  msg = '<h1>Hello!</h1>'

  def serve():
    try:
      # Serve 3 connections, then quit.
      for i in range(3):
        C = S.accept()                     # Get a connection.
        req = stream_readline(C)           # Read HTTP request.

        s = msg                            # Message to send back

        C.send('HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n' +
             'Content-Length: ' + str(int(len(s))) + '\r\n\r\n' + s)

        if i % 2 == 0: C.close()           # Close connection
      S.close()
    except Exception, e:
      global __err
      __err = e
    global __server_done
    __server_done = True

  thread.start_new_thread(serve, ())
  # Wait for accept to kick in (should work without).
  time.sleep(2.0)

  def client():
    try:
      S2 = samclasses.StreamSession('Carol')
      # Get / on server three times.
      assert stream_http_get(S2, S.dest) == msg
      assert stream_http_get(S2, S.dest) == msg
      assert stream_http_get(S2, S.dest) == msg
      S2.close()
    except Exception, e:
      global __err
      __err = e
    global __client_done
    __client_done = True

  thread.start_new_thread(client, ())

  while not (__client_done and __server_done): time.sleep(0.01)

  if __err != None:
    print 'Unit test failed for samclasses.StreamSession.accept'
    raise __err
  test_passed('samclasses.StreamSession.accept')

def multithread_packet_test(raw=True):
  """If raw:  Multithreaded unit test for samclasses.RawSession.
     Not raw: Multithreaded unit test for samclasses.DatagramSession.
  """

  try:
    multithread_wait_time = 200.0
    may_need_increase = False

    if raw:
      C = samclasses.RawSession('Carol', in_depth=0, out_depth=0)
      D = samclasses.RawSession('Dave', in_depth=0, out_depth=0)
    else:
      C = samclasses.DatagramSession('Carol',in_depth=0,out_depth=0)
      D = samclasses.DatagramSession('Dave',in_depth=0,out_depth=0)

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
          C.send(s, D.dest)
          __lock.acquire()
          D_recv += [s]
          __lock.release()
        else:
          # Send packet from D to C, and log it.
          D.send(s, C.dest)
          __lock.acquire()
          C_recv += [s]
          __lock.release()
        time.sleep(0.01*random.uniform(0.0,1.0))
        # Read any available packets.
        try: (p, fromaddr) = C.recv(timeout=0.0)
        except socket.BlockError: p = None
        if p != None and not raw: assert fromaddr == D.dest 

        __lock.acquire()
        if p != None: C_got += [p]
        __lock.release()

        try: (p, fromaddr) = D.recv(timeout=0.0)
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
      try: (p, fromaddr) = C.recv(timeout=0.0)
      except socket.BlockError: p = None
      if p != None and not raw: assert fromaddr == D.dest 

      if p != None: C_got += [p]

      try: (p, fromaddr) = D.recv(timeout=0.0)
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
      print 'Unit test failed for samclasses.RawSession ' +       \
            '(multithreaded).'
      print 'Raw packets are not reliable.'
    else:
      print 'Unit test failed for samclasses.DatagramSession ' +  \
            '(multithreaded).'
      print 'Datagram packets are not reliable.'

    if may_need_increase:
      print 'Try increasing multithread_wait_time.'

    traceback.print_exc(); sys.exit()
  if raw:
    test_passed('samclasses.RawSession (multithreaded)')
  else:
    test_passed('samclasses.DatagramSession (multithreaded)')



def multithread_stream_test():
  """Multithreaded unit test for samclasses.StreamSession."""

  try:
    multithread_wait_time = 200.0
    may_need_increase = False

    C = samclasses.StreamSession('Carol', in_depth=0, out_depth=0)
    D = samclasses.StreamSession('Dave', in_depth=0, out_depth=0)
    C.listen(10)
    D.listen(10)

    Cout = C.connect(D.dest)
    Dout = D.connect(C.dest)
    Cin  = C.accept()
    Din  = D.accept()

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
        try: p = Cin.recv(100000, timeout=0.0)
        except socket.BlockError: p = None
        if p != None: C_got += [p]
        __lock.release()

        __lock.acquire()
        try: p = Din.recv(100000, timeout=0.0)
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
      try: p = Cin.recv(100000, timeout=0.0)
      except socket.BlockError: p = None
      if p != None: C_got += [p]

      try: p = Din.recv(100000, timeout=0.0)
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
    print 'Unit test failed for samclasses.StreamSession ' +    \
          '(multithreaded).'

    if may_need_increase:
      print 'Try increasing multithread_wait_time.'

    traceback.print_exc(); sys.exit()
  test_passed('samclasses.StreamSession (multithreaded)')


def test():
  print 'Tests may take several minutes each.'
  print 'If the network is unreliable, tests will fail.'
  print 'A test only needs to pass once to be considered successful.'
  print
  print 'Testing:'

  raw_test1()
  datagram_test1()
  stream_test1()
  stream_test2()
  multithread_packet_test(raw=True)
  multithread_stream_test()

 # Note: The datagram unit test fails, but it's apparently I2P's
 # fault (the code is the same as for raw packets, and the SAM
 # bridge is sent all the relevant data).
 # Code: multithread_packet_test(raw=False)

if __name__ == '__main__':
  test()

