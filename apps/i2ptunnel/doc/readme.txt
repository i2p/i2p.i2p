I2PTunnel allows to tunnel a usual TCP connection over I2P.

A server needs to have a public key; a client connects to that.

Starting a client:

tunnel <localport> <pubkey>
   localport: the port where you want to connect to
   pubkey:    the public key of the server


Starting a server:

tunnel <remotehost> <remoteport> <privkey>
   remotehost: the host to connect to when a connection comes.
   remoteport: the port to connect to when a connection comes.
   privkey:    your private (secret) key

To stop a client/server, hit <Return>

Have fun!