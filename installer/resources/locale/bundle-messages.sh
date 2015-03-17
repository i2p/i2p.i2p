#
# Update messages_xx.po and .mo files
# Requires installed programs xgettext, msgfmt, msgmerge, and find.
#
# usage:
#    bundle-messages.sh (generates the resource bundle from the .po file)
#    bundle-messages.sh -p (updates the .po file from the source tags, then generates the resource bundle)
#
# zzz - public domain
#
TMPFILE=filelist.txt
export TZ=UTC
RC=0

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

JPATHS=".."
for i in po/messages_*.po
do
	# get language
	LG=${i#po/messages_}
	LG=${LG%.po}

	# skip, if specified
	if [ $LG2 ]; then
		[ $LG != $LG2 ] && continue || echo INFO: Language update is set to [$LG2] only.
	fi

	if [ "$POUPDATE" = "1" ]
	then
		# make list of java files newer than the .po file
		find $JPATHS -maxdepth 1 -name i2prouter -newer $i > $TMPFILE
	fi

	if [ -s mo/$LG/LC_MESSAGES/i2prouter.mo -a \
	     mo/$LG/LC_MESSAGES/i2prouter.mo -nt $i -a \
	     ! -s $TMPFILE ]
	then
		continue
	fi

	if [ "$POUPDATE" = "1" ]
	then
	 	echo "Updating the $i file from the tags..."
		# extract strings from files, and update messages.po files
		find $JPATHS -maxdepth 1 -name i2prouter > $TMPFILE
		xgettext -f $TMPFILE -F -L Shell --from-code=UTF-8 \
		         -o ${i}t
		if [ $? -ne 0 ]
		then
			echo "ERROR - xgettext failed on ${i}, not updating translations"
			rm -f ${i}t
			RC=1
			break
		fi
		msgmerge -U --backup=none $i ${i}t
		if [ $? -ne 0 ]
		then
			echo "ERROR - msgmerge failed on ${i}, not updating translations"
			rm -f ${i}t
			RC=1
			break
		fi
		rm -f ${i}t
		# so we don't do this again
		touch $i
	fi

    if [ "$LG" != "en" ]
    then
        # only generate for non-source language
        echo "Generating $LG ResourceBundle..."

        # convert to class files in build/obj
        mkdir -p mo/$LG/LC_MESSAGES
        msgfmt --statistics -o mo/$LG/LC_MESSAGES/i2prouter.mo $i
        if [ $? -ne 0 ]
        then
            echo "ERROR - msgfmt failed on ${i}, not updating translations"
            # msgfmt leaves the class file there so the build would work the next time
            rm -rf mo/$LG/LC_MESSAGES
            RC=1
            break
        fi
    fi
done
rm -f $TMPFILE
exit $RC
