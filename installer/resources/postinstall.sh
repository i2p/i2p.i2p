#!/bin/sh

# I2P Installer - Installs and pre-configures I2P.
#
# postinstall
# 2004 The I2P Project
# http://www.i2p2.de/
# This code is public domain.
#
# author: hypercubus
#
# Installs the appropriate set of Java Service Wrapper support files for the
# user's OS then launches the I2P router as a background service.

if [ ! "X$1" = "X" ]; then
    cd $1
fi

chmod 744 ./i2prouter
# chmod 744 ./install_i2p_service_unix
chmod 744 ./osid
chmod 744 ./runplain.sh
# chmod 744 ./uninstall_i2p_service_unix

ERROR_MSG="Cannot determine operating system type. From the subdirectory in lib/wrapper matching your operating system, please move i2psvc to your base I2P directory, and move the remaining two files to the lib directory."

HOST_OS=`./osid`

if [ "X$HOST_OS" = "X" -o $HOST_OS = "unknown" ]; then
    echo "$ERROR_MSG"
    exit 1
fi

OS_ARCH=`uname -m`
X86_64=`echo "$OS_ARCH" | grep x86_64`

case $HOST_OS in
    debian | fedora | gentoo | linux | mandrake | redhat | suse )
        if [ "X$X86_64" = "X" ]; then
            wrapperpath="./lib/wrapper/linux"
            cp $wrapperpath/libwrapper.so ./lib/
        else
            wrapperpath="./lib/wrapper/linux64"
            cp $wrapperpath/libwrapper.so ./lib
        fi
        ;;
    freebsd )
        wrapperpath="./lib/wrapper/freebsd"
        cp $wrapperpath/libwrapper.so ./lib/
        ;;
    osx )
        wrapperpath="./lib/wrapper/macosx"
        cp $wrapperpath/libwrapper.jnilib ./lib/
        ;;
    solaris )
        wrapperpath="./lib/wrapper/solaris"
        cp $wrapperpath/libwrapper.so ./lib/
        ;;
    * )
        echo "$ERROR_MSG"
        exit 1
        ;;
esac

cp $wrapperpath/wrapper.jar ./lib/
cp $wrapperpath/i2psvc .
chmod 744 ./eepget
chmod 744 ./i2psvc
chmod 744 ./scripts/i2pbench.sh
chmod 744 ./scripts/i2ptest.sh
rm -rf ./icons
rm -rf ./lib/wrapper
rm -f ./lib/*.dll
rm -f ./*.bat
rm -f ./*.exe
rm -rf ./installer
# no, let's not start the router from the install script any more
# ./i2prouter start
exit 0

