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
CFLAGS += -I../inc -L../lib
LIBS = -lsam

#
# Build rules
#

all: warhammer-dgram

warhammer-dgram:
	$(CC) $(CFLAGS) -o warhammer-dgram.o -c warhammer-dgram.c
	$(CC) $(CFLAGS) -o warhammer-dgram warhammer-dgram.o $(LIBS)

#
# Cleanup rules
#

clean:
	-rm -f *.o *.exe warhammer-dgram
