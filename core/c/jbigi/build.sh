#/bin/sh

VER=4.2.2
echo "Building the jbigi library with GMP Version $VER"

echo "Extracting GMP..."
tar -xjf gmp-$VER.tar.bz2
echo "Building..."
mkdir -p lib/
mkdir -p bin/local
cd bin/local
case `uname -sr` in
Darwin*)
# --with-pic is required for static linking
../../gmp-$VER/configure --with-pic;;
*)
../../gmp-$VER/configure;;
esac
make
sh ../../build_jbigi.sh static
cp *jbigi???* ../../lib/
cd ../..
