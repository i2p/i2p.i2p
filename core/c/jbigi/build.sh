#/bin/sh

echo "Building the jbigi library with GMP"

echo "Extracting GMP..."
tar -xjf gmp-4.1.4.tar.bz2
echo "Building..."
mkdir -p lib/
mkdir -p bin/local
cd bin/local
case `uname -sr` in
Darwin*)
# --with-pic is required for static linking
../../gmp-4.1.4/configure --with-pic;;
*)
../../gmp-4.1.4/configure;;
esac
make
sh ../../build_jbigi.sh static
cp *jbigi???* ../../lib/
cd ../..
