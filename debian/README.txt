This sets up a binary package with the following:

- A new user i2psvc (a lot of people already have an i2p user)
- i2psvc home is /var/lib/i2p
- $I2P is /usr/lib/i2p, owned by i2psvc
- i2psvc router directory is /var/lib/i2p/i2p-config (hack in WorkingDir.java)
- i2p daemon script in /etc/init.d/i2p
- i2prouter and eepget scripts in /usr/bin for other users to run it
- Users other than i2psvc cannot update via i2p, as they don't have
  write permissions in $I2P
- Configured temp directory is /tmp
- linux and linux64 (i386) wrapper libs only


Todo:

- Remove 1MB lib/jbigi.jar, just build and include dynamic libjbigi
  and the linux libjcpuid (and add dependency on libgmp)
- Initial router.config for i2psvc (without confusing i2p that
  the router directory already exists):
  * Disable browser launch at startup
  * Move i2psnark dir, eepsite dir, log dir, etc. up one level
