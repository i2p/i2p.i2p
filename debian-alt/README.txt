The files in ../debian/ are for the current stable distribution (buster).
They should also work with bullseye, experimental, sid, testing.

Alternates are in the subdirectories here.
To use them, copy them over the files in ../debian/  .

Built file compatibility:
trusty may be copied to vivid.
trusty may be used for jessie with libjetty9-java from backports.
xenial may be copied to yakkety, zesty
bionic may be copied to cosmic
disco may be copied to eoan, focal and groovy

Not maintained:
wheezy files are not maintained. Use the precise files instead.
jessie files are not be maintained. Use the trusty files instead.
stretch files may not be maintained. Use the trusty files instead.

Note on systemd:

# 'dh $@ --with systemd' needs dh-systemd which isn't available in Wheezy (except from backports),
# and is only Ubuntu as of "Saucy". The official packages will enable this for Debian unstable and
# Ubuntu Saucy (and newer)

Through release 0.9.25, the debian/ directory was not current for the release;
any changes required for the build were checked in after the release.
Starting with release 0.9.26, we will attempt to test debian builds
before the release, and check in any required changes to debian/
and debian-alt/ before the release, so that the files
may actually be used to build the release.

See additional documentation in the doc/ directory.
