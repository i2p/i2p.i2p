"""
i2p -- I2P Python interface
"""

__all__ = ['Error', 'RouterError', 'sam', 'eep', 'router',
    'I2PSocketServer', 'I2PBaseHTTPServer',
    'I2PSimpleHTTPServer', 'I2PCGIHTTPServer',
    ]

class Error(Exception):
  """Base class for all I2P errors."""

class RouterError(Error):
  """Could not connect to router."""

import sam
import eep
import router

# Internal use only
#import samclasses as _samclasses
