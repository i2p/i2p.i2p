#!/usr/bin/env bash

#FIXME What platforms for MacOS?
MISC_DARWIN_PLATFORMS=""

# Note: You will have to add the CPU ID for the platform in the CPU ID code
# for a new CPU. Just adding them here won't let I2P use the code!

#
# If you know of other platforms i2p on linux works on,
# please add them here.
# Do NOT add any X86 platforms, do that below in the x86 platform list.
#
MISC_LINUX_PLATFORMS="hppa2.0 alphaev56 armv5tel mips64el itanium itanium2 ultrasparc2 ultrasparc2i alphaev6 powerpc970 powerpc7455 powerpc7447"

#
# If you know of other platforms i2p on FREEBSD works on,
# please add them here.
# Do NOT add any X86 platforms, do that below in the x86 platform list.
#
MISC_FREEBSD_PLATFORMS="alphaev56 ultrasparc2i"
MISC_NETBSD_PLATFORMS="powerpc powerpc64 powerpc64le powerpcle m68k-atari amiga m68knommu"

#
# MINGW/Windows??
#
MISC_MINGW_PLATFORMS=""

#
# Are there any other X86 platforms that work on i2p? Add them here.
#

# Note! these build on 32bit as 32bit when operating as 32bit...
X86_64_PLATFORMS="atom athlon64 core2 corei nano pentium4"

# Note! these are 32bit _ONLY_
X86_PLATFORMS="pentium pentiummmx pentium2 pentium3 pentiumm k6 k62 k63 athlon geode viac3 viac32 ${X86_64_PLATFORMS}"


#
# You should not need to edit anything below this comment.
#

MINGW_PLATFORMS="${X86_PLATFORMS} ${MISC_MINGW_PLATFORMS}"
LINUX_PLATFORMS="${X86_PLATFORMS} ${MISC_LINUX_PLATFORMS}"
FREEBSD_PLATFORMS="${X86_PLATFORMS} ${MISC_FREEBSD_PLATFORMS}"
NETBSD_PLATFORMS="${FREEBSD_PLATFORMS} ${LINUX_PLATFORMS} ${MISC_NETBSD_PLATFORMS}"
DARWIN_PLATFORMS="core2 corei"

# Set the version to 5.0.2 for OSX because
# 1) it doesn't have the -r parameter as an option for sed
# 2) AFAIK there are only 64bit capable CPUs for the Intel Macs
if [ `uname -s |grep Darwin` ]; then
  	VER=5.0.2
else
	VER=$(echo gmp-*.tar.bz2 | sed -re "s/(.*-)(.*)(.*.tar.bz2)$/\2/" | tail -n 1)
fi

if [ "$VER" = "" ] ; then
	echo "ERROR! Can't find gmp source tarball."
	exit 1
fi


case `uname -sr` in
MINGW*)
	PLATFORM_LIST="${MINGW_PLATFORMS}"
	NAME="jbigi"
	TYPE="dll"
	TARGET="-windows-"
	echo "Building windows .dlls for all architectures";;
Darwin*)
	PLATFORM_LIST="${DARWIN_PLATFORMS}"
	NAME="libjbigi"
	TYPE="jnilib"
	TARGET="-osx-"
	echo "Building ${TARGET} .jnilibs for all architectures";;
Linux*)
	NAME="libjbigi"
	TYPE="so"
	PLATFORM_LIST=""
	TARGET="-linux-"
	arch=$(uname -m | cut -f1 -d" ")
	case ${arch} in
		i[3-6]86)
			arch="x86";;
	esac
	case ${arch} in
		x86_64)
			PLATFORM_LIST="${X86_64_PLATFORMS}"
			TARGET="-linux-X86_64-";;
		ia64)
			PLATFORM_LIST="${X86_64_PLATFORMS}"
			TARGET="-linux-ia64-";;
		x86)
			PLATFORM_LIST="${X86_PLATFORMS}"
			TARGET="-linux-x86-";;
		*)
			PLATFORM_LIST="${LINUX_PLATFORMS}";;
	esac
	echo "Building ${TARGET} .so's for ${arch}";;
NetBSD*)
	PLATFORM_LIST="${NETBSD_PLATFORMS}"
	NAME="libjbigi"
	TYPE="so"
	TARGET="-netbsd-"
	echo "Building netbsd .sos for all architectures";;
FreeBSD*)
	PLATFORM_LIST="${FREEBSD_PLATFORMS}"
	NAME="libjbigi"
	TYPE="so"
	TARGET="-freebsd-"
	echo "Building freebsd .sos for all architectures";;
*)
	echo "Unsupported build environment"
	exit;;
esac

function make_static {
	echo "Attempting .${4} creation for ${3}${5}${2}"
	../../mbuild_jbigi.sh static || return 1
	cp ${3}.${4} ../../lib/net/i2p/util/${3}${5}${2}.${4}
	return 0
}

function make_file {
	# Nonfatal bail out on Failed build.
	echo "Attempting build for ${3}${5}${2}"
	make && return 0
	cd ..
	rm -R "$2"
	echo -e "\n\nFAILED! ${3}${5}${2} not made.\a"
	sleep 10
	return 1
}

function configure_file {
	echo -e "\n\n\nAttempting configure for ${3}${5}${2}\n\n\n"
	sleep 10
	# Nonfatal bail out on unsupported platform.
	if [ `uname -s |grep Darwin` ]; then
		../../gmp-${1}/configure --build=${2}-apple-darwin --with-pic && return 0
	else
		../../gmp-${1}/configure --build=${2} --with-pic && return 0
	fi
	cd ..
	rm -R "$2"
	echo -e "\n\nSorry, ${3}${5}${2} is not supported on your build environment.\a"
	sleep 10
	return 1
}

function build_file {
	configure_file "$1" "$2" "$3" "$4" "$5"  && make_file "$1" "$2" "$3" "$4" "$5" && make_static "$1" "$2" "$3" "$4" "$5" && return 0
	echo -e "\n\n\nError building static!\n\n\a"
	sleep 10
	return 1
}

echo "Extracting GMP Version $VER ..."
tar -xjf gmp-$VER.tar.bz2 || ( echo "Error in tarball file!" ; exit 1 )

if [ ! -d bin ]; then
	mkdir bin
fi
if [ ! -d lib/net/i2p/util ]; then
	mkdir -p lib/net/i2p/util
fi

# Don't touch this one.
NO_PLATFORM=none

for x in $NO_PLATFORM $PLATFORM_LIST
do
	(
		if [ ! -d bin/$x ]; then
			mkdir bin/$x
			cd bin/$x
		else
			cd bin/$x
			rm -Rf *
		fi

		build_file "$VER" "$x" "$NAME" "$TYPE" "$TARGET"
	)
done

echo "Success!"
exit 0
