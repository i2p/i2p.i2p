
# -------------------------------------------------------------
# makedoc.py: Make pydoc documentation for Python SAM API
# -------------------------------------------------------------

import os, inspect
import pydoc as pydoc_

def pydoc(args):
  """Run pydoc (command line) with given argument string."""
  filename = inspect.getsourcefile(pydoc_)
  os.system('python ' + filename + ' ' + args)

def move(f1, f2):
  """Moves filename f1 to filename f2, overwriting if f2 already exists."""
  try: os.remove(f2)
  except: pass
  os.rename(f1, f2)

def makedoc():
  """Make all HTML documentation for Python I2P library."""
  modules = ['i2p', 'i2p.sam', 'i2p.eep', 'i2p.router', 'i2p.samclasses']

  origdir = os.getcwd()
  os.chdir('../..')

  for m in modules:
    pydoc('-w ' + m)

  os.chdir(origdir)

  for m in modules:
    move('../../' + m + '.html', './' + m + '.html')

if __name__ == '__main__':
  makedoc()