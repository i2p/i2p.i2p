#! /usr/bin/env python

# --------------------------------------------------
# py2exe_demo.py: Sample setup script for py2exe
# --------------------------------------------------

"""
Sample setup script for py2exe.

Use 'python py2exe_demo.py install' to build an exe.

A zip archive of the distribution is about 630 KB
(Delete _ssl.pyd to save space).
"""

from distutils.core import setup
import py2exe
      
setup(console=["../stream_eepget.py"])
