#! /usr/bin/env python

from distutils.core import setup
import os, sys

os.chdir('./src')

if sys.platform == 'win32':
    stasherFrontEnd = "..\\stasher.py"
else:
    stasherFrontEnd = "../stasher"

setup(name="Python I2P API",
      version="0.91",
      description="Python Interface to I2P",
      author="Connelly Barnes + Aum",
      author_email="'Y29ubmVsbHliYXJuZXNAeWFob28uY29t\n'.decode('base64')",
      url="http://www.i2p.net/",
      packages=['i2p', 'i2p.pylib'],
      py_modules=['bencode'],
      scripts=[stasherFrontEnd],
     )
