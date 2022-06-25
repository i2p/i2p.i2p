#!/bin/sh
#
#       This script downloads gmp-6.x.x.tar.bz2 to this directory
#       (if a different version, change the GMP_VER= line below)
#

export GMP_VER=6.2.1
export GMP_TARVER=${GMP_VER}
export GMP_DIR="gmp-$GMP_VER"
export GMP_TAR="gmp-$GMP_TARVER.tar.bz2"


download_tar()
{
  GMP_TAR_URL="https://gmplib.org/download/gmp/${GMP_TAR}"
  if [ $(which wget) ]; then
    echo "Downloading $GMP_TAR_URL"
    wget -N --progress=dot $GMP_TAR_URL
  else
    echo "ERROR: Cannot find wget." >&2
    echo >&2
    echo "Please download $GMP_TAR_URL" >&2
    echo "manually and rerun this script." >&2
    exit 1
  fi
}

extract_tar()
{
  tar -xjf ${GMP_TAR} > /dev/null 2>&1 || (rm -f ${GMP_TAR} && download_tar && extract_tar || exit 1)
}

if [ ! -d "$GMP_DIR" -a ! -e "$GMP_TAR" ]; then
  download_tar
fi

if [ ! -d "$GMP_DIR" ]; then
  extract_tar
fi
