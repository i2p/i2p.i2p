#
# This Makefile is compatible with GNU Make and should work on Linux
# (Tested on Debian 3.0)
#

#
# Directories
#

BINDIR = bin
LOGDIR = log
OBJDIR = obj
SRCDIR = src

SAMINCDIR = ../sam/c/inc
SAMLIBDIR = ../sam/c/lib
TOMCRYPTDIR = $(HOME)/libtomcrypt-0.96

#
# Programs
#

CC = g++

#
# Flags
#

CFLAGS = -g -march=i486 -pipe -Wall

#
# Libraries
#

CFLAGS += -I$(SAMINCDIR) -I$(TOMCRYPTDIR)
LDFLAGS = -L$(SAMLIBDIR) -L$(TOMCRYPTDIR)
LIBS = -lsam -ltomcrypt -lpthread

#
# Object files
#

OBJS =	$(OBJDIR)/bigint.o \
		$(OBJDIR)/chk.o \
		$(OBJDIR)/config.o \
		$(OBJDIR)/logger.o \
		$(OBJDIR)/main.o \
		$(OBJDIR)/mutex.o \
		$(OBJDIR)/peers.o \
		$(OBJDIR)/random.o \
		$(OBJDIR)/rpc.o \
		$(OBJDIR)/sam.o \
		$(OBJDIR)/sha1.o

#
# Build rules
#

all: depend enclave

depend:
	$(CC) $(CFLAGS) -MM $(SRCDIR)/*.cpp > .depend

$(OBJDIR)/%.o: $(SRCDIR)/%.cpp
	$(CC) $(CFLAGS) -o $@ -c $<

enclave: $(OBJS)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $(BINDIR)/enclave $(OBJS) $(LIBS)

#
# Cleanup rules
#

clean:
	-rm -f $(BINDIR)/* $(OBJDIR)/* .depend

clean-logs:
	-rm -f $(LOGDIR)/*

tidy: clean clean-logs
