
# ------------------------------------------------
# Hack to import the Python library modules
# when names conflict in our package.
# ------------------------------------------------

import sys
sys.path.reverse()

import socket
import select
import BaseHTTPServer
import SocketServer
import CGIHTTPServer
import SimpleHTTPServer

sys.path.reverse()
