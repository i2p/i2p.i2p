#/bin/sh

echo "Building the jbigi library with GMP"

echo "Extracting GMP..."
tar -xjf gmp-4.1.4.tar.bz2
echo "Building..."
mkdir -p lib/
mkdir -p bin/local
cd bin/local
../../gmp-4.1.4/configure
make
sh ../../build_jbigi.sh static
cp *jbigi???* ../../lib/
cd ../..
