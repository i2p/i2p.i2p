#
# This Makefile is compatible with GNU Make
#

#
# Programs
#

CC = gcc

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

i2p-ping:
	$(CC) $(CFLAGS) -o i2p-ping.o -c i2p-ping.c
	$(CC) $(CFLAGS) -o i2p-ping i2p-ping.o $(LIBS)

#
# Cleanup rules
#

clean:
	-rm -f *.o *.exe i2p-ping
