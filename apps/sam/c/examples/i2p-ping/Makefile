FLAGS+=-g

CFLAGS = $(FLAGS) -pipe -std=c99 -Wall
CFLAGS += -I../../inc
LDFLAGS = $(FLAGS) -L../../lib -lsam

OBJS:=i2p-ping.lo
DEPS:=$(patsubst obj/%.lo, .deps/%.d, $(OBJS))
DESTDIR:=$(if $(DESTDIR),$(DESTDIR)/lib,/usr/lib)

MAKEFLAGS=-s -r
PERL=$(shell which perl 2>/dev/null)
ifneq ($(PERL),)
STATUS=$(PERL) ../../status
else
STATUS=echo
endif

LIBTOOL_LOG=libtool.log

all:: cleanlog i2p-ping

cleanlog:
	>$(LIBTOOL_LOG)

i2p-ping: $(OBJS)
	$(STATUS) link
	libtool --mode=link $(CC) $(LDFLAGS) -o $@ $^ >>$(LIBTOOL_LOG)

%.lo: %.c
	$(STATUS) compile $*
	libtool --mode=compile $(CC) $(CFLAGS) -Iinc/ -c -o $@ $< >>$(LIBTOOL_LOG)
.deps/%.d: src/%.c
	$(STATUS) deps $*
	$(CC) -Iinc/ -MM -MT obj/$*.o $^ -o $@

clean:
	$(STATUS) clean
	rm -Rf .deps obj libtool.log
	libtool --mode=clean rm -f i2p-ping i2p-ping.lo >>$(LIBTOOL_LOG)

$(OBJS):|obj
obj:
	$(STATUS) MKDIR $@
	mkdir -p $@

# -include $(DEPS)
$(DEPS):|.deps
.deps:
	$(STATUS) MKDIR $@
	mkdir -p $@


.PHONY: all cleanlog clean
