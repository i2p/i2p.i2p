#!/bin/sh
#
# Generate translated man pages from the po files.
#
# can't get po4a to do this
#
# NOTE:
# For new translations, add the file names to debian/i2p.manpages and/or debian/i2p-router.manpages.
# Don't forget to check in those files and .tx/config .
# Don't forget to mtn add and check in new files in man/ and locale-man/ .
#
cd `dirname $0`
for i in eepget i2prouter i2prouter-nowrapper
do
	for f in locale-man/man_*.po
	do
		j=${f%.po}
		j=${j#locale-man/man_}
		po4a-translate -f man -m man/$i.1 -p locale-man/man_$j.po -l man/$i.$j.1 -L UTF-8 -M UTF-8 -k 10 -v -d
	        if [ $? -ne 0 ]
		then
			echo "********* FAILED TRANSLATE FOR $j $i *************"
			FAIL=1
		fi
	done
done
exit $FAIL
