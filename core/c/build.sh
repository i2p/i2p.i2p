#/bin/sh

(cd jcpuid ; sh build.sh ; cd ..)
(cd jbigi ; sh build.sh ; cd ..)

mkdir -p t/freenet/support/CPUInformation/
cp jcpuid/lib/freenet/support/CPUInformation/*jcpuid* t/freenet/support/CPUInformation/

mkdir -p t/net/i2p/util/
cp jbigi/lib/net/i2p/util/*jbigi* t/net/i2p/util/
cp jbigi/lib/*jbigi* t/

(cd t ; jar cf ../jbigi.jar . ; cd ..)

echo "Native code built into jbigi.jar"
