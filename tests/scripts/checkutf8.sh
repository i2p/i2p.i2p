#!/bin/sh
#
# Check for UTF-8 problems in all files where they might appear
# Also check all Java source files
# Returns nonzero on failure
#
# zzz 2010-12
# public domain
#

cd `dirname $0`/../..

# apps/routerconsole/jsp/ should only have UTF8 in help_xx.jsp

DIRS="\
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
  debian/po \
  installer/resources/eepsite/docroot/help \
  installer/resources/initialNews \
  installer/resources/proxy \
  installer/resources/readme \
  apps/routerconsole/jsp \
  apps/i2ptunnel/jsp \
  apps/susidns/src/jsp"

for i in `find $DIRS -maxdepth 1 -type f`
do
	#echo "Checking $i ..."
	iconv -f UTF8 -t UTF8 $i -o /dev/null
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
done

echo "Checking all Java and Scala files ..."
for i in `find . \( -name \*.java -o -name \*.scala \) -type f`
do
	#echo "Checking $i ..."
	iconv -f UTF8 -t UTF8 $i -o /dev/null
	if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
done

# Java properties files (when not using our DataHelper methods) must be ISO-8859-1
# https://docs.oracle.com/javase/6/docs/api/java/util/Properties.html
echo "Checking getopt properties files ..."
for i in `find core/java/src/gnu/getopt -name \*.properties -type f`
do
	#echo "Checking $i ..."
	iconv -f ISO-8859-1 -t ISO-8859-1 $i -o /dev/null
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
