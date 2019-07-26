#!/usr/bin/env bash
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
I2P_SOURCE_DIR="$(realpath $SCRIPT_DIR/../..)"

if [[ -z "$1"  ]]; then
  echo "You must run the script with the build number as first argument. Example ./script 6"
  exit 1
fi
BUILD_NUMBER="$1"

cd $I2P_SOURCE_DIR
ant mavenLocal || (echo "Build failed!!!" && exit 1)

# Append right build number to jar files (and everything else but we don't care)
cd $I2P_SOURCE_DIR/pkg-mavencentral || (echo "Build failed!!!" && exit 1)
for file in `ls -1 *.jar`; do
  filename=$(basename -- "$file")
  extension="${filename##*.}"
  filename="${filename%.*}"
  mv $file "${filename}-${BUILD_NUMBER}.${extension}"
done

filename=$(ls -1 i2p*jar)
tmp=${filename#*-}
version=${tmp%.*}
echo "I2P is ${version}"

# Finally, install to maven.
mvn install:install-file \
    -Dpackaging=jar \
    -DgroupId=net.i2p \
    -DartifactId=i2p \
    -Dversion=$version -Dfile="i2p-${version}.jar"

mvn install:install-file \
    -Dpackaging=jar \
    -DgroupId=net.i2p \
    -DartifactId=router \
    -Dversion=$version -Dfile="router-${version}.jar"

mvn install:install-file \
    -Dpackaging=jar \
    -DgroupId=net.i2p.client \
    -DartifactId=mstreaming \
    -Dversion=$version -Dfile="mstreaming-${version}.jar"

mvn install:install-file \
    -Dpackaging=jar \
    -DgroupId=net.i2p.client \
    -DartifactId=streaming \
    -Dversion=$version -Dfile="streaming-${version}.jar"


