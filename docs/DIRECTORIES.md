# Short directory explaination

This list should give any new developer a kickstart in where to find code that they wish to modify.

Also nice for old developers with weak memory :)

Directory | Description
 -------  |  ---------
`apps` | This directory contains applications and clients that ships with i2p.
`apps/addressbook` | Some headless code for addressbook management.
`apps/apparmor` | Ruleset for AppArmor.
`apps/BOB` | Code for the BOB service.
`apps/i2psnark` | Code for i2psnark, the torrent client component in webconsole.
`apps/i2ptunnel` | Code for the Hidden Service Manager, and it's GUI in webconsole.
`apps/jetty` | Jetty webserver code.
`apps/routerconsole` | The router console code.
`apps/sam` | SAM service.
`apps/streaming` | The streaming part.
`apps/susidns` | Code for the addressbook component in the webconsole.
`apps/susimail` | Code for the mail client component in the webconsole.
`installer` | This directory contains the code for the installer.
`installer/resources` | Used for static files that's packed with i2p.
`core/java` | Common core code used both by the rotuer and apps.
`core/java/src/net/i2p/app` | Code for app interface.
`core/java/src/net/i2p/crypto` | This directory contain most of the crypto code.
`core/java/src/net/i2p/client` | Client interface code (I2PClient, I2PSession etc.).
`core/java/src/net/i2p/socks` | SOCKS implementation.
`core/java/src/net/i2p/update` | Parts of the update code.
`core/java/src/net/i2p/util` | Utillity code like Log, FileUtil, EepGet, HexDump, and so on.
`router/java` | This directory contains the I2P router code.
`router/java/src/net/i2p/data/i2np` | I2NP code, the inner protocol for I2P.
`router/java/src/net/i2p/router/startup` | Code related to the startup sequence.
`router/java/src/net/i2p/router/networkdb/kademlia` | The DHT (kademlia) code.
`router/java/src/net/i2p/router/networkdb/reseed` | The reseed code.
`router/java/src/net/i2p/router/transport` | Transport implementation code (NTCP, SSU).
`router/java/src/net/i2p/router/tunnel` | Tunnel implementation code.

 