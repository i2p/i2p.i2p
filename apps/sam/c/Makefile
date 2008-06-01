FLAGS+=-g
CFLAGS+=$(FLAGS)
LDFLAGS+=$(FLAGS)

OBJS:=obj/sam.lo obj/strl.lo obj/parse.lo obj/tinystring.lo
DEPS:=$(patsubst obj/%.lo, .deps/%.d, $(OBJS))
DESTDIR:=$(if $(DESTDIR),$(DESTDIR)/lib,/usr/lib)

MAKEFLAGS=-s -r

PERL=$(shell which perl 2>/dev/null)
ifneq ($(PERL),)
STATUS=$(PERL) ./status
else
STATUS=echo
endif

LIBTOOL_LOG=libtool.log

all:: cleanlog .deps/finish

cleanlog:
	echo >$(LIBTOOL_LOG)

lib/libsam.so: obj/libsam.la
	libtool --mode=install install $^ `pwd`/$@ >>$(LIBTOOL_LOG)

obj/libsam-static.la: $(OBJS)
	$(STATUS) library '(static)'
	libtool --mode=link $(CC) -static $(LDFLAGS) -o $@ $^ >>$(LIBTOOL_LOG)

obj/libsam.la: $(OBJS)
	$(STATUS) library '(shared)'
	libtool --mode=link $(CC) -rpath  $(DESTDIR) $(LDFLAGS) -o $@ $^ >>$(LIBTOOL_LOG)

obj/%.lo: src/%.c
	$(STATUS) compile $*
	libtool --mode=compile $(CC) $(CFLAGS) -Iinc/ -c -o $@ $< >>$(LIBTOOL_LOG)

$(OBJS):|obj
obj:
	$(STATUS) MKDIR $@
	mkdir -p $@

.deps/%.d: src/%.c
	$(STATUS) deps $*
	$(CC) -Iinc/ -MM -MT obj/$*.o $< -o $@

-include $(DEPS)

DEPS+=.deps/finish
.deps/finish: lib/libsam.so
	libtool --finish $(DESTDIR) >>$(LIBTOOL_LOG) && touch $@
$(DEPS):|.deps
.deps:
	$(STATUS) MKDIR $@
	mkdir -p $@

clean:
	$(STATUS) clean
	libtool --mode=clean rm -f obj/*.l* lib/*.l* lib/*.so* lib/*.a >>$(LIBTOOL_LOG)
	rm -Rf .deps libtool.log

.PHONY: all cleanlog clean
