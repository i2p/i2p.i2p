#
# This Makefile is compatible with GNU Make
#

#
# Programs
#

CC = gcc
INSTALL = install

#
# Flags
#

CFLAGS = -g -O2 -pipe -std=c99 -Wall
CFLAGS += -I../../inc -L../../lib
LIBS = -lsam

#
# Build rules
#

all: i2p-ping

i2p-ping: i2p-ping.c
	$(CC) $(CFLAGS) -o i2p-ping.o -c i2p-ping.c
	$(CC) $(CFLAGS) -o i2p-ping i2p-ping.o $(LIBS)

install: i2p-ping
	$(INSTALL) i2p-ping $(HOME)/bin

#
# Cleanup rules
#

clean:
	-rm -f *.o *.exe i2p-ping
