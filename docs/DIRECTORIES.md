# Short directory explaination

This list should give any new developer a kickstart in where to find code that they wish to modify.

Also nice for old developers with weak memory :)

Directory | Description
 -------  |  ---------
`apps` | This directory contains applications and clients that ships with i2p.
`apps/addressbook` | Some headless code for addressbook management.
`apps/apparmor` | Ruleset for AppArmor.
`apps/BOB` | Code for the BOB service.
`apps/desktopgui` | The new system tray application
`apps/i2psnark` | Code for i2psnark, the torrent client component in webconsole.
`apps/i2ptunnel` | Code for the Hidden Service Manager, and its GUI in webconsole.
`apps/imagegen` | The image generator webapp
`apps/jetty` | Jetty webserver code.
`apps/jrobin` | Graph package for the console
`apps/ministreaming` | The streaming (TCP-like socket) interface.
`apps/routerconsole` | The router console code.
`apps/routerconsole/java/src/net/i2p/router/news` | The news feed subsystem
`apps/routerconsole/java/src/net/i2p/router/update` | The automatic update subsystem
`apps/routerconsole/java/src/net/i2p/router/web` | Java code for the console, including plugin support
`apps/routerconsole/jsp` | Jsps for the console
`apps/sam` | SAM service.
`apps/streaming` | The streaming (TCP-like socket) implementation.
`apps/susidns` | Code for the addressbook component in the webconsole.
`apps/susimail` | Code for the mail client component in the webconsole.
`apps/systray` | The old system tray application, now removed, and some related utilities
`installer` | This directory contains the code for the installer.
`installer/lib/izpack` | Installer libraries
`installer/lib/jbigi` | jbigi and jcpuid DLLs
`installer/lib/launch4j` | Windows jar-to-exe binary
`installer/lib/wrapper` | Wrapper binaries and libraries
`installer/resources` | Used for static files that are packed with i2p.
`core/java` | Common core code used both by the rotuer and apps.
`core/java/src/net/i2p/app` | Code for app interface.
`core/java/src/net/i2p/client` | Low-level client I2CP interface (I2PClient, I2PSession etc.).
`core/java/src/net/i2p/client/impl` | Client-side I2CP implementation
`core/java/src/net/i2p/client/naming` | Addressbook interfaces and base implementation
`core/java/src/net/i2p/crypto` | This directory contain most of the crypto code.
`core/java/src/net/i2p/data` | Common data structures and data-related utilities
`core/java/src/net/i2p/internal` | Internal socketless I2CP connections
`core/java/src/net/i2p/kademlia` | Base Kademlia implementation used by the router and i2psnark
`core/java/src/net/i2p/socks` | SOCKS client implementation.
`core/java/src/net/i2p/stat` | Statistics subsystem.
`core/java/src/net/i2p/time` | Internal time representation
`core/java/src/net/i2p/update` | Parts of the update code.
`core/java/src/net/i2p/util` | Utillity code like Log, FileUtil, EepGet, HexDump, and so on.
`router/java` | This directory contains the I2P router code.
`router/java/src/net/i2p/data/i2np` | I2NP code, the inner protocol for I2P.
`router/java/src/net/i2p/data/router` | Router data structures such as RouterInfo
`router/java/src/net/i2p/router/client` | Router-side I2CP implementation
`router/java/src/net/i2p/router/crypto` | Router crypto not needed in the core library
`router/java/src/net/i2p/router/dummy` | Dummy implementation of some subsystems for testing
`router/java/src/net/i2p/router/message` | Garlic message creation and parsing
`router/java/src/net/i2p/router/networkdb/kademlia` | The DHT (kademlia) code.
`router/java/src/net/i2p/router/networkdb/reseed` | The reseed code.
`router/java/src/net/i2p/router/peermanager` | Peer profile tracking and storage
`router/java/src/net/i2p/router/startup` | Code related to the startup sequence.
`router/java/src/net/i2p/router/transport` | Transport implementation code (NTCP, SSU).
`router/java/src/net/i2p/router/transport/crypto` | Transport crypto (DH)
`router/java/src/net/i2p/router/tasks` | Small router helpers, run periodically
`router/java/src/net/i2p/router/tunnel` | Tunnel implementation code.
`router/java/src/net/i2p/router/util` | Router utilities

 
