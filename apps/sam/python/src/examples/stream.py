
# -----------------------------------------------
# stream.py: Simple stream client
# -----------------------------------------------

from i2p import sam
        
S = sam.socket('Alice', sam.SOCK_STREAM)
S.connect('duck.i2p')
S.send('GET / HTTP/1.0\r\n\r\n')      # Send request
print S.recv(1000)                    # Read up to 1000 bytes
