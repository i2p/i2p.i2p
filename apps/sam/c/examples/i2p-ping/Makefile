#
# This Makefile is compatible with GNU Make and should work on POSIX systems
#

#
# Programs
#

CC = gcc
INSTALL = install
RM = rm

#
# Flags
#

CFLAGS = -g -O2 -pipe -std=c99 -Wall
CFLAGS += -I../../inc -L../../lib
LIBS = -lsam

#
# Build rules
#

all: clean i2p-ping

i2p-ping: i2p-ping.c
	$(CC) $(CFLAGS) -o i2p-ping.o -c i2p-ping.c
	$(CC) $(CFLAGS) -o i2p-ping i2p-ping.o $(LIBS)

install: i2p-ping
	$(INSTALL) i2p-ping $(HOME)/bin

#
# Cleanup rules
#

clean:
	-$(RM) -f i2p-ping *.o
