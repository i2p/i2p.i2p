
# -----------------------------------------------------
# test_eep.py: Unit tests for eep.py.
# -----------------------------------------------------

# Make sure we can import i2p
import sys; sys.path += ['../../']

import traceback, sys
from i2p import eep

def verify_html(s):
  """Raise an error if s does not end with </html>"""
  assert s.strip().lower()[-7:] == '</html>'

def eepget_test():
  try:
    verify_html(eep.urlget('http://duck.i2p/index.html'))
    verify_html(eep.urlget('http://duck.i2p/'))
    verify_html(eep.urlget('http://duck.i2p'))
    verify_html(eep.urlget('duck.i2p/'))
    verify_html(eep.urlget('duck.i2p'))
  except Exception, e:
    print 'Unit test failed for eepget'
    print "Note that urllib2.urlopen uses IE's proxy settings " +  \
          "in Windows."
    print "This may cause " +                                      \
          "urllib2.urlopen('http://www.google.com/') to fail."
    traceback.print_exc(); sys.exit()
  print 'eepget:    OK'

def test():
  eepget_test()

if __name__ == '__main__':
  print 'Testing:'
  test()
