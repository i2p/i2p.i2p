#! /usr/bin/env python

"""
Make epydoc HTML documentation in the 'html' subdirectory.
"""

import epydoc as epydoc_
import inspect
import os, sys

def epydoc(args):
  """Run epydoc (command line) with given argument string."""
  os.system('python calldoc.py ' + args)

def makedoc():
  """Make all epydoc HTML documentation for Python I2P library."""
  modlist = [
    'i2p',
    'i2p.eep',
    'i2p.tunnel',
    'i2p.router',
    'i2p.socket',
    'i2p.select',
    'i2p.samclasses',
    'i2p.CGIHTTPServer',
    'i2p.SimpleHTTPServer',
    'i2p.BaseHTTPServer',
    'i2p.SocketServer',
    'i2p.pylib'
  ]
  modlist.reverse()
  epydoc('--html ' + ' '.join(modlist))

if __name__ == '__main__':
  makedoc()
