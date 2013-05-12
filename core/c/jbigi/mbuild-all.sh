#!/bin/sh

# ON Solaris 11 (at least) this variable must be set.
# Linux and *BSD will do the right thing.
#
#BITS=32

# FIXME Is this all?
DARWIN_PLATFORMS="core2 corei"
MISC_DARWIN_PLATFORMS="powerpc powerpc64 powerpc64le powerpcle"

# Note: You will have to add the CPU ID for the platform in the CPU ID code
# for a new CPU. Just adding them here won't let I2P use the code!

#
# If you know of other platforms i2p on linux works on,
# please add them here.
# Do NOT add any X86 platforms, do that below in the x86 platform list.
#
MISC_LINUX_PLATFORMS="hppa2.0 alphaev56 armv5tel mips64el itanium itanium2 ultrasparc2 ultrasparc2i alphaev6 powerpc970 powerpc7455 powerpc7447"

#
# If you know of other platforms i2p on *BSD works on,
# please add them here.
# Do NOT add any X86 platforms, do that below in the x86 platform list.
#
MISC_FREEBSD_PLATFORMS="alphaev56 ultrasparc2i"
MISC_NETBSD_PLATFORMS="armv5tel mips64el ultrasparc2i sgi hppa2.0 alphaev56 powerpc powerpc64 powerpc64le powerpcle atari amiga m68knommu" # and many, many more
MISC_OPENBSD_PLATFORMS="alphaev56 ultrasparc2i sgi powerpc powerpc64 hppa2.0 alphaev56 armv5tel mips64el"

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

MINGW_PLATFORMS="${X86_PLATFORMS} ${MISC_MINGW_PLATFORMS}"
LINUX_PLATFORMS="${X86_PLATFORMS} ${MISC_LINUX_PLATFORMS}"
FREEBSD_PLATFORMS="${X86_PLATFORMS} ${MISC_FREEBSD_PLATFORMS}"
# As they say, "Of course it runs NetBSD!"
NETBSD_PLATFORMS="${FREEBSD_PLATFORMS} ${MISC_LINUX_PLATFORMS} ${MISC_NETBSD_PLATFORMS}"
OPENBSD_PLATFORM="${X86_PLATFORMS} ${MISC_OPENBSD_PLATFORMS}"

#
# You should not need to edit anything below this comment.
#

# If JAVA_HOME isn't set we'll try to figure it out
[ -z $JAVA_HOME ] && . ../find-java-home
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'" >&2
    echo "Please set JAVA_HOME to a java home that has the JNI" >&2
    exit 1
fi

if [ ! $(which m4)  ]; then
    printf "\aWARNING: \`m4\` not found. If this process fails to complete, install m4 " >&2
    printf "and re-run this script.\n\n\n\a" >&2
    sleep 10
fi


# Allow TARGET to be overridden (e.g. for use with cross compilers)
[ -z $TARGET ] && TARGET=$(uname -s |tr "[A-Z]" "[a-z]")


# Set the version to 5.0.2 for OSX because AFAIK there are only 64bit capable CPUs for the Intel Macs
# FIXME do this without sed (and tail) (= portably)
if [ `echo $TARGET|grep darwin` ]; then
        VER=5.0.2
elif [ `echo $TARGET|grep sunos` ]; then
        VER=$(echo gmp-*.tar.bz2 | sed -e "s/\(.*-\)\(.*\)\(.*.tar.bz2\)$/\2/" | /usr/xpg4/bin/tail -n 1)
else
        VER=$(echo gmp-*.tar.bz2 | sed -e "s/\(.*-\)\(.*\)\(.*.tar.bz2\)$/\2/" | tail -n 1)
fi

if [ "$VER" = "" ] ; then
        echo "ERROR! Can't find gmp source tarball."
        exit 1
fi

# If the BITS variable isn't set above we'll proceed without setting the *FLAGS
# variables ourselves.
[ -z $BITS ] && BITS=0

if [ $BITS -eq 32 ]; then
    export CC="gcc -m32"
    export CFLAGS="-m32"
    export LDFLAGS="-m32"
    SUFFIX=
elif [ $BITS -eq 64 ]; then
    export CC="gcc -m64"
    export CFLAGS="-m64"
fi

case "$TARGET" in
mingw*)
        PLATFORM_LIST="${MINGW_PLATFORMS}"
        NAME="jbigi"
        TYPE="dll"
        TARGET="windows"
        echo "Building windows .dlls for all architectures";;
darwin*)
        PLATFORM_LIST="${DARWIN_PLATFORMS}"
        NAME="libjbigi"
        TYPE="jnilib"
        TARGET="osx"
        echo "Building ${TARGET} .jnilibs for all architectures";;
sunos*)
        PLATFORM_LIST="${X86_64_PLATFORMS}"
        NAME="libjbigi"
        TYPE="so"
        UNIXTYPE="solaris"
        TARGET="${UNIXTYPE}"
        if $(echo "$CFLAGS" | grep -q "\-m64") ; then
            [ -z $SUFFIX ] && SUFFIX="_64"
            PLATFORM_LIST="${X86_64_PLATFORMS}"
        else
            PLATFORM_LIST="${X86_PLATFORMS}"
        fi
        echo "Building ${TARGET} .sos for all architectures";;
linux*|*kfreebsd)
        NAME="libjbigi"
        TYPE="so"
        PLATFORM_LIST=""
        case "$TARGET" in
            *kfreebsd)
                TARGET="kfreebsd"
                ;;
            *)
                TARGET="linux"
                ;;
        esac
        arch=$(uname -m | cut -f1 -d" ")
        case ${arch} in
                i[3-6]86)
                        arch="x86";;
        esac
        case ${arch} in
                x86_64 | amd64)
                        PLATFORM_LIST="${X86_64_PLATFORMS}"
                        if [ $BITS -ne 32 ]; then
                            [ -z $SUFFIX ] && SUFFIX="_64"
                        fi
                        ;;
                #ia64)
                #        PLATFORM_LIST="${X86_64_PLATFORMS}"
                #        TARGET="$TARGET-ia64";;
                x86)
                        PLATFORM_LIST="${X86_PLATFORMS}";;
                *)
                        PLATFORM_LIST="${LINUX_PLATFORMS}";;
        esac
        echo "Building ${TARGET} .sos for ${arch}";;
netbsd*|freebsd*|openbsd*)
        NAME="libjbigi"
        TYPE="so"
        PLATFORM_LIST=
        arch=$(uname -m | cut -f1 -d" ")
        case ${arch} in
                i[3-6]86)
                        arch="x86";;
        esac
        case ${arch} in
                x86_64|amd64)
                        PLATFORM_LIST="${X86_64_PLATFORMS}"
                       [ -z $SUFFIX ] && SUFFIX="_64";;
                #ia64)
                #        PLATFORM_LIST="${X86_64_PLATFORMS}"
                #        SUFFIX="{SYS}-ia64";;
                x86)
                        PLATFORM_LIST="${X86_PLATFORMS}";;
                *)
                        case ${TARGET} in
                                netbsd)
                                        PLATFORM_LIST="${NETBSD_PLATFORMS}";;
                                openbsd)
                                        PLATFORM_LIST="${OPENBSD_PLATFORMS}";;
                                freebsd)
                                        PLATFORM_LIST="${FREEBSD_PLATFORMS}";;
                                *)
                                        echo "Unsupported build environment"
                                        exit 1;;
                        esac
        esac
        echo "Building ${TARGET} .sos for ${arch}";;
*)
        echo "Unsupported build environment"
        exit;;
esac

#####################
# In the below functions:
# $1 = gmp version
# $2 = platform: such as athlon64
# $3 = basename: "jbigi" on Windows, "libjbigi" everywhere else
# $4 = type/extension: windows = "dll". osx = "jnilib". Everything else = "so"
# $5 = target: "linux", "freebsd", "kfreebsd", "osx", "windows", etc.
# $6 = suffix: null if 32bit, _64 if 64bit

make_static () {
        echo "Attempting .${4} creation for ${3}${5}${2}${6}"
        ../../build_jbigi.sh static || return 1
        cp ${3}.${4} ../../lib/net/i2p/util/${3}${5}${2}${6}.${4}
        return 0
}

make_file () {
        # Nonfatal bail out on Failed build.
        echo "Attempting build for ${3}${5}${2}"
        make && return 0
        cd ..
        rm -R "$2"
        printf "\n\nFAILED! ${3}${5}${2} not made.\a"
        sleep 10
        return 1
}

configure_file () {
        printf "\n\n\nAttempting configure for ${3}${5}${2}${6}\n\n\n"
        if [ $BITS -eq 32 ] && [ "$2"  = "none" ]; then
            unset ABI
        elif [ $BITS -eq 32 ] && [ "$2" != "none" ]; then
            export ABI=32
        fi
        sleep 10
        # Nonfatal bail out on unsupported platform.
        if [ $(echo $TARGET| grep -q osx) ]; then
                ../../gmp-${1}/configure --build=${2}-apple-darwin --with-pic && return 0
        else
                ../../gmp-${1}/configure --build=${2} --with-pic && return 0
        fi
        cd ..
        rm -R "$2"
        printf "\n\nSorry, ${3}${5}${2} is not supported on your build environment.\a"
        sleep 10
        return 1
}

build_file () {
        configure_file "$1" "$2" "$3" "$4" "$5" "$6"  && make_file "$1" "$2" "$3" "$4" "$5" "$6" && make_static "$1" "$2" "$3" "$4" "$5" "$6" && return 0
        printf "\n\n\nError building static!\n\n\a"
        sleep 10
        return 1
}

echo "Extracting GMP Version $VER ..."
if [ -e gmp-$VER.tar.bz2 ]; then
    tar -xjf gmp-$VER.tar.bz2 || ( echo "Error in tarball file!" >&2 ; exit 1 )
else
    echo "ERROR: gmp tarball not found in current directory" >&2
    exit 1
fi

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

                build_file "$VER" "$x" "$NAME" "$TYPE" "-$TARGET-" "$SUFFIX"
        )
done

echo "Success!"
exit 0

# vim:fenc=utf-8:ai:si:ts=4:sw=4:et:nu
