#!/bin/sh
#
# Run with BITS=32 to generate 32-bit libs on a 64-bit platform
# On Ubuntu you will need sudo apt-get install gcc-multilib libc6-i386 libc6-dev-i386
#

# ON Solaris 11 (at least) this variable must be set.
# Linux and *BSD will do the right thing.
#

#
# look in configure file in gmp source for supported host CPUs, at about line 5000
#


# Note: You will have to add the CPU ID for the platform in the CPU ID code
# for a new CPU. Just adding them here won't let I2P use the code!

#
# If you know of other platforms i2p on linux works on,
# please add them here.
# Do NOT add any X86 platforms, do that below in the x86 platform list.
#
MISC_LINUX_PLATFORMS="hppa2.0 alphaev56 mips64el itanium itanium2 ultrasparc2 ultrasparc2i alphaev6 powerpc970 powerpc7455 powerpc7447"

#
# If you know of other platforms i2p on *BSD works on,
# please add them here.
# Do NOT add any X86 platforms, do that below in the x86 platform list.
#
MISC_FREEBSD_PLATFORMS="alphaev56 ultrasparc2i"
MISC_NETBSD_PLATFORMS="armv5tel mips64el ultrasparc2i sgi hppa2.0 alphaev56 powerpc powerpc64 powerpc64le powerpcle atari amiga m68knommu" # and many, many more
MISC_OPENBSD_PLATFORMS="alphaev56 ultrasparc2i sgi powerpc powerpc64 hppa2.0 alphaev56 armv5tel mips64el"

#
# ARM
#
# These platforms exist as of GMP 6.0.0.
# Some of them will be renamed in the next version of GMP.
ARM_PLATFORMS="armv5 armv6 armv7a armcortex8 armcortex9 armcortex15"
# Rename output of armv7a to armv7 since that's what NBI expects.
# This is due to versions after GMP 6.0.0 changing the target name.
TRANSLATE_NAME_armv7a="armv7"
# aarch64
TRANSLATE_NAME_aarch64="armv8"

#
# X86_64
#
# Are there any other X86 platforms that work on i2p? Add them here.
#
# Note! these build on 32bit as 32bit when operating as 32bit...
# starting with k10 added for 6.0.0
# As of GMP 6.0.0, libgmp 3,
X86_64_PLATFORMS="zen2 zen silvermont goldmont skylake coreisbr coreihwl coreibwl bobcat jaguar bulldozer piledriver steamroller excavator atom athlon64 core2 corei nano pentium4 k10 x86_64"
TRANSLATE_NAME_x86_64="none" # Rename x86_64 to none_64, since that is what NativeBigInteger refers to it as

# Note! these are 32bit _ONLY_ (after the 64 bit ones)
# Also note that the 64-bit entry "x86_64" is filtered out since it already has the more appropriate "i386" entry
X86_PLATFORMS="$(echo $X86_64_PLATFORMS | sed 's/x86_64//g') pentium pentiummmx pentium2 pentium3 pentiumm k6 k62 k63 athlon geode viac3 viac32 i386"
TRANSLATE_NAME_i386="none" # Rename i386 to none, , since that is what NativeBigInteger refers to it as

DARWIN_PLATFORMS="core2 corei coreisbr coreihwl coreibwl"
MINGW_PLATFORMS="${X86_PLATFORMS} ${MISC_MINGW_PLATFORMS}"
LINUX_PLATFORMS="${X86_PLATFORMS} ${MISC_LINUX_PLATFORMS}"
FREEBSD_PLATFORMS="${X86_PLATFORMS} ${MISC_FREEBSD_PLATFORMS}"
# As they say, "Of course it runs NetBSD!"
NETBSD_PLATFORMS="${FREEBSD_PLATFORMS} ${MISC_LINUX_PLATFORMS} ${MISC_NETBSD_PLATFORMS}"
OPENBSD_PLATFORM="${X86_PLATFORMS} ${MISC_OPENBSD_PLATFORMS}"

# Android
# https://developer.android.com/ndk/guides/other_build_systems
ANDROID_ARM64_PLATFORMS="aarch64"
ANDROID_ARM_PLATFORMS="armv7a"
ANDROID_x86_64_PLATFORMS="x86_64"
ANDROID_x86_PLATFORMS="i686"


# Import gmp version variables and download gmp.
. ./download_gmp.sh

# If JAVA_HOME isn't set we'll try to figure it out
[ -z $JAVA_HOME ] && . ../find-java-home
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "Cannot find jni.h! Looked in '$JAVA_HOME/include/jni.h'" >&2
    echo "Please set JAVA_HOME to a java home that has the JNI" >&2
    exit 1
fi

if [ ! $(which m4) ]; then
    printf "\aERROR: \`m4\` not found. Install m4 " >&2
    printf "and re-run this script.\n\n\n\a" >&2
    exit 1
fi


if [ -z $BITS ]; then
  UNAME="$(uname -m)"
  if test "${UNAME#*x86_64}" != "$UNAME"; then
    BITS=64
  elif test "${UNAME#*i386}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*i686}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*armv6}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*armv7}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*aarch32}" != "$UNAME"; then
    BITS=32
  elif test "${UNAME#*aarch64}" != "$UNAME"; then
    BITS=64
  else
    echo "Unable to detect default setting for BITS variable"
    exit 1
  fi
  printf "\aBITS variable not set, $BITS bit system detected\n\a" >&2
fi


if [ -z $CC ]; then
  export CC="gcc"
  printf "\aCC variable not set, defaulting to $CC\n\a" >&2
fi

# Allow TARGET to be overridden (e.g. for use with cross compilers)
[ -z $TARGET ] && TARGET=$(uname -s |tr "[A-Z]" "[a-z]")

if [ $BITS -eq 32 ]; then
  export ABI=32
  if [ "$TARGET" != "android" ]; then
      export CFLAGS="-m32"
      export LDFLAGS="-m32"
  fi
elif [ $BITS -eq 64 ]; then
  export ABI=64
  if [ "$TARGET" != "android" -a "$UNAME" != "aarch64" ]; then
      export CFLAGS="-m64"
      export LDFLAGS="-m64"
  fi
else
  printf "\aBITS value \"$BITS\" not valid, please select 32 or 64\n\a" >&2
  exit 1
fi

if [ ! $(which ${CC}) ]; then
  echo "The compiler you've selected \"$CC\" does not appear to exist"
  exit 1
fi

# Set the "_64" filname filename suffix for 64-bit builds
if [ $BITS -ne 32 ] && [ "$UNAME" != "aarch64" ] || [ "$ANDROID_FORCE_ARM" != "true" ]; then
    [ -z $SUFFIX ] && SUFFIX="_64"
fi

# Note, this line does not support windows (and needs to generate a win32/win64 string for that to work)
BUILD_OS=$(uname -s | tr "[A-Z]" "[a-z]")

# Do some sanity checks for when we're cross-compiling and
# set up host string, ARCH_VENDOR_OS. The "\$2" will be replaced
# with the name of the arch at configuration time
if [ "$TARGET" != "$BUILD_OS" ]; then
  case "$TARGET" in
  windows*)
    HOST_CONFIGURE_FLAG="\$2-w64-mingw32"
    case "$CC" in
    *i*86*mingw32*gcc)
      [ $BITS -ne 32 ] && echo "Error, 32-bit cross-compiler used with non 32-bit architecture" && exit 1
      ;;
    *x86_64*mingw32*gcc)
      [ $BITS -ne 64 ] && echo "Error, 64-bit cross-compiler used with non 64-bit architecture" && exit 1
      ;;
    *)
      echo "No recognized cross-compiler provided in CC env variable."
      [ $BITS -eq 32 ] && echo "For 32-bit targets, i686-w64-mingw32-gcc is recommended"
      [ $BITS -eq 64 ] && echo "For 64-bit targets, x86_64-w64-mingw32-gcc is recommended"
      exit 1;
      ;;
    esac
  ;;
  freebsd*)
    HOST_CONFIGURE_FLAG="\$2-pc-freebsd"
  ;;
  darwin*|osx)
    HOST_CONFIGURE_FLAG="\$2-darwin"
#     case "$CC" in
#     *i*86*darwin*)


#       [ $BITS -ne 32 ] && echo "Error, 32-bit cross-compiler used with non 32-bit architecture" && exit 1
      ;;
#     *x86_64*darwin*)
#       HOST_CONFIGURE_FLAG="\$2-apple-darwin"
#       [ $BITS -ne 64 ] && echo "Error, 64-bit cross-compiler used with non 64-bit architecture" && exit 1
#       ;;
#     *)
#       echo "No recognized cross-compiler provided in CC env variable."
#       [ $BITS -eq 32 ] && echo "For 32-bit targets, i686-apple-darwin10-gcc recommended"
#       [ $BITS -eq 64 ] && echo "For 64-bit targets, x86_64-apple-darwin10-gcc recommended"
#       exit 1;
#       ;;
#     esac
#   ;;
  android)
    ANDROID_NDK=`realpath ../../../../android-ndk-r25c`
    export TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64
    ARCH=$(uname -m | cut -f1 -d" ")
    android_arm () {
      if [ $BITS -eq 32 ]; then
        HOST_CONFIGURE_FLAG=armv7a-linux-androideabi
        export AR=$TOOLCHAIN/bin/llvm-ar
        export AS=$TOOLCHAIN/bin/llvm-as
        export CC=$TOOLCHAIN/bin/armv7a-linux-androideabi19-clang
        export CXX=$TOOLCHAIN/bin/armv7a-linux-androideabi19-clang++
        export LD=$TOOLCHAIN/bin/llvm-ld
        export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
        export STRIP=$TOOLCHAIN/bin/llvm-strip
      else
        HOST_CONFIGURE_FLAG=aarch64-linux-android
        export AR=$TOOLCHAIN/bin/llvm-ar
        export AS=$TOOLCHAIN/bin/llvm-as
        export CC=$TOOLCHAIN/bin/aarch64-linux-android21-clang
        export CXX=$TOOLCHAIN/bin/aarch64-linux-android21-clang++
        export LD=$TOOLCHAIN/bin/llvm-ld
        export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
        export STRIP=$TOOLCHAIN/bin/llvm-strip
      fi    
    }

    if [ "$ANDROID_FORCE_ARM" == "true" ]; then
      android_arm
    else
      case ${ARCH} in
          x86_64 | amd64 | i*86)
        if [ $BITS -eq 32 ]; then
          HOST_CONFIGURE_FLAG=i686-linux-android
          export AR=$TOOLCHAIN/bin/llvm-ar
          export AS=$TOOLCHAIN/bin/llvm-as
          export CC=$TOOLCHAIN/bin/i686-linux-android19-clang
          export CXX=$TOOLCHAIN/bin/i686-linux-android19-clang++
          export LD=$TOOLCHAIN/bin/llvm-ld
          export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
          export STRIP=$TOOLCHAIN/bin/llvm-strip
        else
          HOST_CONFIGURE_FLAG=x86_64-linux-android
          export AR=$TOOLCHAIN/bin/llvm-ar
          export AS=$TOOLCHAIN/bin/llvm-as
          export CC=$TOOLCHAIN/bin/x86_64-linux-android21-clang
          export CXX=$TOOLCHAIN/bin/x86_64-linux-android21-clang++
          export LD=$TOOLCHAIN/bin/llvm-ld
          export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
          export STRIP=$TOOLCHAIN/bin/llvm-strip
        fi
          ;;
        *arm*)
          android_arm
          ;;
      esac
    fi
    ;;
  esac
fi

case "$TARGET" in
mingw*|windows*)
        NAME="jbigi"
        TYPE="dll"
        TARGET="windows"
        if [ $BITS -ne 32 ]; then
                PLATFORM_LIST="${X86_64_PLATFORMS}"
        else
                PLATFORM_LIST="${X86_PLATFORMS}"
        fi
        echo "Building ${TARGET} .dlls for all architectures";;
darwin*|osx)
        NAME="libjbigi"
        TYPE="jnilib"
        TARGET="osx"
        PLATFORM_LIST="${DARWIN_PLATFORMS}"
        echo "Building ${TARGET} .jnilibs for all architectures";;
sunos*)
        NAME="libjbigi"
        TYPE="so"
        BUILD_OS="solaris"
        TARGET="${BUILD_OS}"
        if [ $BITS -eq 32 ]; then
            PLATFORM_LIST="${X86_PLATFORMS}"
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
        ARCH=$(uname -m | cut -f1 -d" ")

        case ${ARCH} in
                x86_64 | amd64 | i*86)
                        if [ $BITS -eq 32 ]; then
                          PLATFORM_LIST="${X86_PLATFORMS}"
                          ARCH="x86"
                        else
                          PLATFORM_LIST="${X86_64_PLATFORMS}"
                          ARCH="x86_64"
                        fi;;
                arm*)
                        PLATFORM_LIST="${ARM_PLATFORMS}";;
                aarch64)
                        PLATFORM_LIST="aarch64";;
                *)
                        PLATFORM_LIST="${LINUX_PLATFORMS}";;
        esac
        echo "Building ${TARGET} .sos for ${ARCH}";;
netbsd*|freebsd*|openbsd*)
        NAME="libjbigi"
        TYPE="so"
        PLATFORM_LIST=
        ARCH=$(uname -m | cut -f1 -d" ")
        case ${ARCH} in
                x86_64 | amd64 | i*86)
                        if [ $BITS -eq 32 ]; then
                          PLATFORM_LIST="${X86_PLATFORMS}"
                          ARCH="x86"
                        else
                          PLATFORM_LIST="${X86_64_PLATFORMS}"
                          ARCH="x86_64"
                        fi;;
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
        echo "Building ${TARGET} .sos for ${ARCH}";;
android)
        NAME="libjbigi"
        TYPE="so"
        ARCH=$(uname -m | cut -f1 -d" ")
        android_arm () {
        if [ $BITS -eq 32 ]; then
            PLATFORM_LIST="${ANDROID_ARM_PLATFORMS}"
            ARCH="armv7a"
        else
            PLATFORM_LIST="${ANDROID_ARM64_PLATFORMS}"
            ARCH="aarch64"
        fi
        }
        if [ "$ANDROID_FORCE_ARM" == "true" ]; then
          android_arm
        else
          case ${ARCH} in
            x86_64 | amd64 | i*86)
          if [ $BITS -eq 32 ]; then
              PLATFORM_LIST="${ANDROID_x86_PLATFORMS}"
              ARCH="i686"
          else
              PLATFORM_LIST="${ANDROID_x86_64_PLATFORMS}"
              ARCH="x86_64"
          fi
          ;;
            *)
          android_arm
          ;;
          esac
        fi
        echo "Building Android .so for ${PLATFORM_LIST}";;
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
        PLATFORM="${2}"
        
        # Some platforms have different build-time names from
        # what java and NativeBigInteger refers to them as.
        # Translate to the proper name here
        eval TRANSLATED_NAME=\$TRANSLATE_NAME_$PLATFORM
        if [ -n "$TRANSLATED_NAME" ]; then
            PLATFORM="${TRANSLATED_NAME}"
        fi
        
        cp ${3}.${4} ../../lib/net/i2p/util/${3}${5}${PLATFORM}${6}.${4}
        return 0
}

make_file () {
        # Nonfatal bail out on Failed build.
        echo "Attempting build for ${3}${5}${2}"
        make -j && return 0
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

        # Nonfatal bail out on unsupported platform.
        (cd ../../gmp-${1}; make clean)
        if [ "$TARGET" != "$BUILD_OS" ]; then
            # We're cross-compiling, supply a --host flag
            
            # Here we're making sure that the platform we're target is injected into
            # the HOST_CONFIGURE_FLAG string. The string looks somehing like this
            # before the eval: "$2_VENDOR_OS"
            # and this after:  "x86_VENDOR_OS"
            eval HOST_CONFIGURE_FLAG=$HOST_CONFIGURE_FLAG
            echo "../../gmp-${1}/configure --host=${HOST_CONFIGURE_FLAG} --with-pic && return 0"
            ../../gmp-${1}/configure --host=${HOST_CONFIGURE_FLAG} --with-pic && return 0
        else
            # We're not cross-compiling, we are however building
            # optimized versions for other platforms on our OS.
            echo "../../gmp-${1}/configure --build=${2}-${BUILD_OS} --with-pic && return 0"
            ../../gmp-${1}/configure --build=${2}-${BUILD_OS} --with-pic && return 0
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


if [ ! -d bin ]; then
        mkdir bin
fi

if [ ! -d lib/net/i2p/util ]; then
        mkdir -p lib/net/i2p/util
fi

for x in $PLATFORM_LIST
do
  (
    if [ ! -d bin/$x ]; then
      mkdir bin/$x
      cd bin/$x
    else
      cd bin/$x
      rm -Rf *
    fi
    build_file "$GMP_VER" "$x" "$NAME" "$TYPE" "-$TARGET-" "$SUFFIX"
  )
done

echo "Success!"
exit 0

# vim:fenc=utf-8:ai:si:ts=4:sw=4:et:nu
