
# -------------------------------------------------------------
# router.py: Router control module
# -------------------------------------------------------------

"""
Router control module
"""

import i2p
import i2p.socket
import i2p.eep
from i2p.pylib import socket as pysocket   # Import Python socket

import os, sys
import os.path
import time
import threading
import urllib2

check_addrlist = [i2p.socket.samaddr, i2p.eep.eepaddr]

router_config = 'router.config'     # Router config filename

# True if our Python program started the router
our_router = False
our_router_lock = threading.Lock()


def find(dir=None):
  """Find the absolute path to a locally installed I2P router.

     An I2P installation is located by looking in the
     the dir argument given to the function, then in the
     environment I2P, then in PATH.  It looks for startRouter.sh
     or startRouter.bat.  Raises ValueError if an I2P installation
     could not be located.
  """
  sep = os.pathsep        # Path separator
  L = []
  if dir != None and dir != '': L += dir.split(sep)
  if 'I2P' in os.environ: L += os.environ['I2P'].split(sep)
  if 'PATH' in os.environ: L += os.environ['PATH'].split(sep)
  for dirname in L:
    filename = os.path.join(dirname, 'startRouter.bat')
    if os.path.exists(filename):
      return dirname
    filename = os.path.join(dirname, 'startRouter.sh')
    if os.path.exists(filename):
      return dirname
  raise ValueError('I2P installation not found')


def check(dir=None):
  """Checks whether a locally installed router is running.  Does
     nothing if successful, otherwise raises i2p.RouterError.

     An I2P installation is located by using find(dir).
     The router.config file is parsed for 'router.adminPort'.
     This port is queried to determine whether the router is
     running.
  """
  config = _parse_config(os.path.join(find(dir), router_config))
  port = config.get('router.adminPort', '')
  try:
    port = int(port)
  except:
    raise ValueError('router.adminPort missing or bad in ' +
                     router_config)

  try:
    s = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
    s.connect(('127.0.0.1', port))
    s.close()
  except pysocket.error:
    raise i2p.RouterError('could not contact 127.0.0.1:' + str(port))

def _run_program(filename):
  """Runs the given program in a new process and new terminal."""
  if sys.platform[:3] == 'win':
    os.startfile(filename)
    global our_router
    our_router = True
  else:
    # Linux possibilities:
    # sh -c command
    # xterm -e command
    # bash -c command
    # Try os.spawnl() with the above.
    raise ValueError('unimplemented')

def start(dir=None, hidden=False):
  """Start a locally installed I2P router.  Does nothing if
     the router has already been started.

     An I2P installation is located by using find(dir).

     If hidden is True, do not show a terminal for the router.
  """
  routerdir = find(dir)
  router = os.path.join(routerdir, 'startRouter.bat')
  try:
    check(dir)
    return         # Already running
  except:
    pass           # Not yet running

  olddir = os.getcwd()

  if hidden:
    raise ValueError('unimplemented')

  our_router_lock.acquire()
  try:
    os.chdir(routerdir)
    try:
      _run_program(router)
    finally:
      os.chdir(olddir)
  finally:
    our_router_lock.release()

  # Ideas for hidden=True:
  # Parse startRouter.bat, and run same command with javaw
  # on Windows to hide command box.
  # Perhaps use javaw (?) or javaws (j2sdk1.4.2/jre/javaws/javaws)
  # Perhaps /path-to/program 2>/dev/null 1>/dev/null&

def _parse_config(filename):
  """Return a dict with (name, value) items for the given I2P configuration file."""
  f = open(filename, 'r')
  s = f.read()
  f.close()
  ans = {}
  for line in s.split('\n'):
    line = line.strip()
    if '#' in line: line = line[:line.find('#')]
    pair = line.split('=')
    if len(pair) == 2:
      ans[pair[0].strip()] = pair[1].strip()
  return ans

def stop(dir=None, force=False):
  """Stop a locally installed I2P router, if it was started by
     the current Python program.  If force is True, stop the
     router even if it was started by another process.  Do nothing
     if force is False and the router was started by another program.

     The file 'router.config' is located using the same search
     process used for find(dir).  It is parsed for
     'router.shutdownPassword' and 'router.adminPort'.  The
     router is shut down through the admin port.

     Raises i2p.RouterError if the I2P router is running but cannot
     be stopped.  You must uncomment the
     'router.shutdownPassword' line for this command to work.
  """
  if force == False and our_router == False:
    return

  config = _parse_config(os.path.join(find(dir), router_config))
  
  password = config.get('router.shutdownPassword', '')
  if password == '':
    raise ValueError('router.shutdownPassword not found in ' +
                     router_config)
  admin_port = config.get('router.adminPort', '')
  if admin_port == '':
    raise ValueError('router.adminPort not found in ' + router_config)

  try:
    admin_port = int(admin_port)
  except:
    raise ValueError('invalid router.adminPort in ' + router_config)

  try:
    sock = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
    sock.connect(('127.0.0.1', admin_port))
    sock.send('GET /shutdown?password=' + password + ' HTTP/1.0\r\n\r\n')
    time.sleep(0.01)
    sock.close()
  except:
    raise i2p.RouterError('router shutdown failed')

  # Assume shutdown succeeded (it will take 30 seconds).
