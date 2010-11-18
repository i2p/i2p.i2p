#!/bin/sh
# This script takes one argument, which is the architecture to build for,
# or "source".

echo Target architecture: $1
VERSION=`grep String\ VERSION ../core/java/src/net/i2p/CoreVersion.java | cut -d\" -f2`
echo "I2P Version: ${VERSION}"
builddir="packages/$1/i2p-${VERSION}"

# dpkg-buildpackage doesn't have an option to change the output directory,
# so copy or symlink i2p.i2p in ${builddir} and call dpkg-buildpackage
# in that directory. The output files are generated one directory above.
if [ "$1" = "source" ]; then
    cd ..
    ant clean
    # dpkg-source (called by dpkg-buildpackage) can't handle symlinks, so copy the i2p.i2p tree
    tempdir=../_i2p_copy_temp___
    cp -a . ${tempdir}
    mkdir -p debian/packages/$1
    mv ${tempdir} debian/${builddir}
    cd debian/${builddir}
    dpkg-buildpackage -I_MTN -Idebian/packages -S
else
    mkdir -p ${builddir}
    cd ${builddir}
    # make symlinks for all files and dirs in i2p.i2p
    find ../../../.. -not -name . -and -not -name .. -maxdepth 1 -exec ln -fs {} \;
    dpkg-buildpackage -I_MTN -b -a$1
fi
if [ "$?" -ne 0 ]; then
  exit 1
fi

cd ../../..
rm -rf ${builddir}
