"""
i2p -- I2P Python interface
"""

__all__ = [
 'BaseHTTPServer',
 'CGIHTTPServer',
 'eep',
 'router',
 'select',
 'SimpleHTTPServer',
 'socket',
 'SocketServer',
 'tunnel',
]

class Error(Exception):
  """Base class for all I2P errors."""

class RouterError(Error):
  """Could not connect to router."""

