
# -------------------------------------------------------------
# eep.py: Eeproxy access module
# -------------------------------------------------------------

"""
Eeproxy access module
"""

import urllib2

eepaddr   = '127.0.0.1:4444'    # Default port for eeproxy

# --------------------------------------------------
# Functions
# --------------------------------------------------

def urlopen(url, eepaddr=eepaddr):
  """Like urllib2.urlopen(url), but only works for eep-sites.
     Example: f = urlopen('http://duck.i2p/index.html')"""
  if url.find('http://') != 0: url = 'http://' + url
  
  # Handle I2P Destination
  if len(url) >= 256:
    suffix = url[len('http://'):]
    if suffix[:4] != 'i2p/': url = 'http://i2p/' + suffix

  # Add trailing slash
  if url.find('/', len('http://')) < 0: url = url + '/'

  # Remove http:// and trailing slash from eepaddr.
  if eepaddr.find('http://') == 0: eepaddr = eepaddr[len('http://'):]
  eepaddr = eepaddr.rstrip('/')

  proxy    = urllib2.ProxyHandler(                                 \
             {'http': 'http://' + eepaddr})
  opener   = urllib2.build_opener(proxy, urllib2.HTTPHandler)
  return opener.open(url)

def urlget(url, eepaddr=eepaddr):
  """Get contents of an eepsite.
     Example: urlget('http://duck.i2p/')."""
  f = urlopen(url, eepaddr=eepaddr)
  ans = f.read()
  f.close()
  return ans
