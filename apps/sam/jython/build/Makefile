# This makefile constructs the SAM Server i2psam.jar

# by default, it gets linked into a standalone .jar file, together
# with the I2P core classes, and the jython classes

# set this to wherever your jython.jar lives
JYTHONJAR=/usr/share/jython/jython.jar

# sources
JYTHON_SOURCES=../src/i2psam.py

# jython compilation command
JYTHON_COMPILE=jython

# change this to wherever you put your i2p jars
CLASSPATH=../../../../build/i2p.jar:../../../../build/mstreaming.jar:$(JYTHONJAR)

# comment this out to prevent the kitchen sink (i2p code, jython code etc)
# from being linked into jar. Uncomment it to create a standalone .jar
JARLINKOPTIONS=--all

# uncomment this to enable '--addpackages' option, but this doesn't
# seem to work, so we don't use it.
#EXTRAPACKAGES=--addpackages net.i2p,net.i2p.client.mstreaming

jar: i2psam.jar

i2psam.jar: $(JYTHON_SOURCES) Makefile
	CLASSPATH=$(CLASSPATH) \
		$(JYTHON_COMPILE) \
		--jar i2psam.jar \
		$(JARLINKOPTIONS) \
		$(EXTRAPACKAGES) \
		$(JYTHON_SOURCES)

