#!/bin/sh

# I2P Installer - Installs and pre-configures I2P.
#
# postinstall
# 2004 The I2P Project
# http://www.i2p.net
# This code is public domain.
#
# author: hypercubus
#
# Installs the appropriate set of Java Service Wrapper support files for the
# user's OS then launches the I2P router as a background service.

if [ $1 ]; then
    cd $1
fi

chmod 744 ./i2prouter
chmod 744 ./install_i2p_service_unix
chmod 744 ./osid
chmod 744 ./uninstall_i2p_service_unix

ERROR_MSG="Cannot determine operating system type. From the subdirectory in lib/wrapper matching your operating system, please move i2psvc to your base I2P directory, and move the remaining two files to the lib directory."
HOST_OS=`./osid`

if [[ ! $HOST_OS || $HOST_OS = "unknown" ]]; then
    echo "$ERROR_MSG"
    exit 1
fi

case $HOST_OS in
    debian | fedora | gentoo | linux | mandrake | redhat | suse )
        wrapperpath="./lib/wrapper/linux"
        ;;
    freebsd )
        wrapperpath="./lib/wrapper/freebsd"
        ;;
    osx )
        wrapperpath="./lib/wrapper/macosx"
        ;;
    solaris )
        wrapperpath="./lib/wrapper/solaris"
        ;;
    * )
        echo "$ERROR_MSG"
        exit 1
        ;;
esac

cp $wrapperpath/i2psvc .
chmod 744 ./i2psvc
cp $wrapperpath/* ./lib/
./i2prouter start
exit 0
