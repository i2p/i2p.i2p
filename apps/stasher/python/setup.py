#! /usr/bin/env python
#@+leo-ver=4
#@+node:@file setup-stasher.py
#@@first
"""
This is the installation script for Stasher, a distributed
file storage framework for I2P.
"""

import sys, os
from distutils.core import setup

oldcwd = os.getcwd()
os.chdir("src")

if sys.platform == 'win32':
    stasherScript = "..\\scripts\\stasher.py"
else:
    stasherScript = "../scripts/stasher"


try:
    import i2p
    import i2p.socket
    import i2p.select
except:
    print "Sorry, but you don't seem to have the core I2P"
    print "python library modules installed."
    print "If you're installing from cvs, please go to"
    print "i2p/apps/sam/python, become root, and type:"
    print "  python setup.py install"
    print "Then, retry this installation."
    sys.exit(1)

setup(name="Stasher",
      version="0.0",
      description="Kademlia-based P2P distributed file storage app for I2P",
      author="aum",
      author_email="aum_i2p@hotmail.com",
      url="http://stasher.i2p",
      py_modules = ['stasher', 'bencode'],
      scripts = [stasherScript],
     )
#@nonl
#@-node:@file setup-stasher.py
#@-leo
