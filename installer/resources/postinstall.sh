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

chmod 755 ./i2prouter
# chmod 755 ./install_i2p_service_unix
chmod 755 ./osid
chmod 755 ./runplain.sh
# chmod 755 ./uninstall_i2p_service_unix

ERROR_MSG="Cannot determine operating system type. From the subdirectory in lib/wrapper matching your operating system, please move i2psvc to your base I2P directory, and move the remaining two files to the lib directory."
LOGFILE=./postinstall.log

HOST_OS=`./osid`

if [ "X$HOST_OS" = "X" -o "X$HOST_OS" = "Xunknown" ]; then
    echo "$ERROR_MSG"
    echo "Host OS is $HOST_OS" >> $LOGFILE
    echo "Host architecture is $OS_ARCH" >> $LOGFILE
    echo "$ERROR_MSG" >> $LOGFILE
    exit 1
fi

OS_ARCH=`uname -m`
X86_64=`echo "${OS_ARCH}" | grep x86_64`

case $HOST_OS in
    debian | fedora | gentoo | linux | mandrake | redhat | suse )
        if [ `echo $OS_ARCH |grep armv7` ]; then
            wrapperpath="./lib/wrapper/linux-armv7"
            cp ${wrapperpath}/libwrapper.so ./lib/
        elif [ `echo $OS_ARCH |grep arm` ]; then
            wrapperpath="./lib/wrapper/linux-armv5"
            cp ${wrapperpath}/libwrapper.so ./lib/
        elif [ "X$X86_64" = "X" ]; then
            wrapperpath="./lib/wrapper/linux"
            cp ${wrapperpath}/libwrapper.so ./lib/
        else
            wrapperpath="./lib/wrapper/linux64"
            cp ${wrapperpath}/libwrapper.so ./lib
	    # the 32bit libwrapper.so will be needed if a 32 bit jvm is used
	    cp ./lib/wrapper/linux/libwrapper.so ./lib/libwrapper-linux-x86-32.so
        fi
        ;;
    freebsd )
	if [ ! `echo $OS_ARCH | grep amd64` ]; then
	    wrapperpath="./lib/wrapper/freebsd"
	    cp ${wrapperpath}/libwrapper.so ./lib/
	else
	    wrapperpath="./lib/wrapper/freebsd64"
	    cp ${wrapperpath}/libwrapper.so ./lib/
	    # the 32bit libwrapper.so will be needed if a 32 bit jvm is used
	    cp ./lib/freebsd/libwrapper.so ./lib/libwrapper-freebsd-x86-32.so
	fi
        ;;
    osx )
        wrapperpath="./lib/wrapper/macosx"
        cp ${wrapperpath}/libwrapper.jnilib ./lib/
	chmod 755 ./Start\ I2P\ Router.app/Contents/MacOS/i2prouter
        ;;
    solaris )
        wrapperpath="./lib/wrapper/solaris"
        cp ${wrapperpath}/libwrapper.so ./lib/
        ;;
    * )
        echo "${ERROR_MSG}"
        echo "Host OS is $HOST_OS" >> $LOGFILE
        echo "Host architecture is $OS_ARCH" >> $LOGFILE
        echo "$ERROR_MSG" >> $LOGFILE
        exit 1
        ;;
esac

cp $wrapperpath/i2psvc .
chmod 755 ./eepget
chmod 755 ./i2psvc
rm -rf ./icons
rm -rf ./lib/wrapper
rm -f ./lib/*.dll
rm -f ./*.bat
rm -f ./*.exe
rm -rf ./installer

if [ ! `echo $HOST_OS  |grep osx` ]; then
    rm -rf ./Start\ I2P\ Router.app
    #rm -f I2P\ Router\ Console.webloc
fi

# no, let's not start the router from the install script any more
# ./i2prouter start
exit 0

