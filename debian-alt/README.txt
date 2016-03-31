The files in ../debian/ are for launchpad (trusty/utopic/vivid/wily).
Alternates are in the subdirectories here.

tails-jessie and tails-wheezy are currently the same as
jessie and wheezy, respectively. If they diverge, put the changes here.

Note on systemd:

# 'dh $@ --with systemd' needs dh-systemd which isn't available in Wheezy (except from backports),
# and is only Ubuntu as of "Saucy". The official packages will enable this for Debian unstable and
# Ubuntu Saucy (and newer)

Files are extracted from the following
packages on deb.i2p2.no and will need to be updated
for 0.9.25 or later:

wheezy:
i2p_0.9.23-2~deb7u+1.debian.tar.xz
precise:
i2p_0.9.23-2~precise+1.debian.tar.xz
jessie:
i2p_0.9.24-1~deb8u+1.debian.tar.xz
unstable:
i2p_0.9.24-1.debian.tar.xz
