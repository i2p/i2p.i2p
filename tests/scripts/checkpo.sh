#!/bin/sh
#
# Run 'msgfmt -c' on all .po files
# Returns nonzero on failure
#
# zzz 2011-02
# public domain
#

cd `dirname $0`/../..

DIRS="\
  core/locale \
  router/locale \
  apps/routerconsole/locale \
  apps/routerconsole/locale-news \
  apps/routerconsole/locale-countries \
  apps/i2ptunnel/locale \
  apps/i2ptunnel/locale-proxy \
  apps/i2psnark/locale \
  apps/ministreaming/locale \
  apps/susidns/locale \
  apps/susimail/locale \
  apps/desktopgui/locale \
  installer/resources/locale/po \
  installer/resources/locale-man \
  debian/po"

FILES="installer/resources/locale-man/man.pot"

for i in `find $DIRS -maxdepth 1 -type f -name \*.po` $FILES
do
	echo "Checking $i ..."
	msgfmt -c $i -o /dev/null
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
done

if [ "$FAIL" != "" ]
then
	echo "******** At least one file failed check *********"
else
	echo "All files passed"
fi
exit $FAIL
