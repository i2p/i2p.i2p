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
# Mathiasdm - modifications for desktopgui
#
CLASS=net.i2p.desktopgui.messages
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

# add ../java/ so the refs will work in the po file
JPATHS="src"
for i in locale/messages_*.po
do
	# get language
	LG=${i#locale/messages_}
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

	if [ -s build/net/i2p/desktopgui/messages_$LG.class -a \
	     build/net/i2p/desktopgui/messages_$LG.class -nt $i -a \
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

        # convert to class files in build
        msgfmt --java --statistics -r $CLASS -l $LG -d build $i
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
