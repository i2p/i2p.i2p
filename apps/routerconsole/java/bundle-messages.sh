#
# Update messages_xx.po and messages_xx.class files,
# from both java and jsp sources.
# Requires installed programs xgettext, msgfmt, msgmerge, and find.
#
# usage:
#    bundle-messages.sh (generates the resource bundle from the .po file)
#    bundle-messages.sh -p (updates the .po file from the source tags, then generates the resource bundle)
#
# zzz - public domain
#
CLASS=net.i2p.router.web.messages
TMPFILE=build/javafiles.txt
export TZ=UTC

if [ "$1" = "-p" ]
then
	POUPDATE=1
fi

# on windows, one must specify the path of commnad find
# since windows has its own retarded version of find.
if which find|grep -q -i windows ; then
	export PATH=.:/bin:/usr/local/bin:$PATH
fi
# Fast mode - update ondemond
# set LG2 to the language you need in envrionment varibales to enable this

#
# generate strings/Countries.java from ../../../installer/resources/countries.txt
#
CFILE=../../../installer/resources/countries.txt
# add ../java/ so the refs will work in the po file
JFILE=../java/build/Countries.java
if [ $CFILE -nt $JFILE -o ! -s $JFILE ]
then
	mkdir -p build
        echo '// Automatically generated pseudo-java for xgettext - do not edit' > $JFILE
	echo '// Translators may wish to translate a few of these, do not bother to translate all of them!!' >> $JFILE
	sed 's/..,\(..*\)/_("\1");/' $CFILE >> $JFILE
fi

# list specific files in core/ and router/ here, so we don't scan the whole tree
ROUTERFILES="\
   ../../../core/java/src/net/i2p/data/DataHelper.java \
   ../../../router/java/src/net/i2p/router/RouterThrottleImpl.java \
   ../../../router/java/src/net/i2p/router/tunnel/pool/BuildHandler.java \
   ../../../router/java/src/net/i2p/router/transport/TransportManager.java \
   ../../../router/java/src/net/i2p/router/transport/GetBidsJob.java \
   ../../../router/java/src/net/i2p/router/Blocklist.java \
   ../../../router/java/src/net/i2p/router/transport/ntcp/EstablishState.java \
   ../../../router/java/src/net/i2p/router/networkdb/reseed/Reseeder.java \
   ../../../router/java/src/net/i2p/router/transport/CommSystemFacadeImpl.java \
   ../../../router/java/src/net/i2p/router/transport/ntcp/NTCPTransport.java \
   ../../../router/java/src/net/i2p/router/transport/udp/UDPTransport.java \
   ../../../router/java/src/net/i2p/router/transport/udp/UPnP.java \
   ../../../router/java/src/net/i2p/router/transport/udp/UPnPManager.java"

# add ../java/ so the refs will work in the po file
JPATHS="../java/src ../jsp/WEB-INF ../java/strings $JFILE $ROUTERFILES"
for i in ../locale/messages_*.po
do
	# get language
	LG=${i#../locale/messages_}
	LG=${LG%.po}
	
	# skip, if specified
	if [ $LG2 ]; then
		[ $LG != $LG2 ] && continue || echo INFO: Language update is set to [$LG2] only.
	fi

	if [ "$POUPDATE" = "1" ]
	then
		# make list of java files newer than the .po file
		find $JPATHS -name *.java -newer $i > $TMPFILE
	fi

	if [ -s build/obj/net/i2p/router/web/messages_$LG.class -a \
	     build/obj/net/i2p/router/web/messages_$LG.class -nt $i -a \
	     ! -s $TMPFILE ]
	then
		continue
	fi

	if [ "$POUPDATE" = "1" ]
	then
	 	echo "Updating the $i file from the tags..."
		# extract strings from java and jsp files, and update messages.po files
		# translate calls must be one of the forms:
		# _("foo")
		# _x("foo")
		# intl._("foo")
		# intl.title("foo")
		# handler._("foo")
		# formhandler._("foo")
		# net.i2p.router.web.Messages.getString("foo")
		# In a jsp, you must use a helper or handler that has the context set.
		# To start a new translation, copy the header from an old translation to the new .po file,
		# then ant distclean updater.
		find $JPATHS -name *.java > $TMPFILE
		xgettext -f $TMPFILE -F -L java --from-code=UTF-8 --add-comments\
	                 --keyword=_ --keyword=_x --keyword=intl._ --keyword=intl.title \
	                 --keyword=handler._ --keyword=formhandler._ \
	                 --keyword=net.i2p.router.web.Messages.getString \
		         -o ${i}t
		if [ $? -ne 0 ]
		then
			echo 'Warning - xgettext failed, not updating translations'
			rm -f ${i}t
			break
		fi
		msgmerge -U --backup=none $i ${i}t
		if [ $? -ne 0 ]
		then
			echo 'Warning - msgmerge failed, not updating translations'
			rm -f ${i}t
			break
		fi
		rm -f ${i}t
		# so we don't do this again
		touch $i
	fi

    if [ "$LG" != "en" ]
    then
        # only generate for non-source language
        echo "Generating ${CLASS}_$LG ResourceBundle..."

        # convert to class files in build/obj
        msgfmt --java --statistics -r $CLASS -l $LG -d build/obj $i
        if [ $? -ne 0 ]
        then
            echo 'Warning - msgfmt failed, not updating translations'
            break
        fi
    fi
done
rm -f $TMPFILE
# todo: return failure
exit 0
