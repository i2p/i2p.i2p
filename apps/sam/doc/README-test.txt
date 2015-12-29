To run tests:

Build and run standalone Java I2CP (no router):
ant buildTest
java -cp build/i2ptest.jar:build/routertest.jar -Djava.library.path=. net.i2p.router.client.LocalClientManager


Build and run standalone SAM server:
ant buildSAM
java -cp build/i2p.jar:build/mstreaming.jar:build/streaming.jar:build/sam.jar -Djava.library.path=. net.i2p.sam.SAMBridge


Build Java test clients:
cd apps/sam/java
ant clientjar
cd ../../..

Run sink client:
mkdir samsinkdir
java -cp build/i2p.jar:apps/sam/java/build/samclient.jar net.i2p.sam.client.SAMStreamSink samdest.txt samsinkdir -v 3.2
run with no args to see usage

Run send client:
java -cp build/i2p.jar:apps/sam/java/build/samclient.jar net.i2p.sam.client.SAMStreamSend samdest.txt samtestdata -v 3.2
run with no args to see usage
