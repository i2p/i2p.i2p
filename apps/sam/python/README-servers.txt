# This package includes the standard Python socket server modules,
# SocketServer, BaseHTTPServer, SimpleHTTPServer and CGIHTTPServer,
# hacked for compatibility with I2P Sam python sockets.
# 
# To avoid clashing the standard python modules, you should
# take care when importing the I2P-ised versions so:
#     'import SocketServer' becomes 'import i2p.SocketServer'
#     'import BaseHTTPServer' -> 'import i2p.BaseHTTPServer'
#     'import SimpleHTTPServer' -> 'import i2p.SimpleHTTPServer'
#     'import CGIHTTPServer' -> 'import i2p.CGIHTTPServer'
# 
# Enclosed here is a minimally simple yet working httpd, in file
# 'example_httpd.py'.
# 
