#!/usr/bin/env bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd ../../..
ant preppkg-osx-only
cd pkg-temp
zip -r7 $DIR/base.zip *
echo "[+] Done building base.zip from ant's pkg-temp."
ninja appbundle

