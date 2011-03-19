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
  apps/i2ptunnel/locale \
  apps/i2psnark/locale \
  apps/susidns/locale \
  apps/susimail/locale \
  apps/desktopgui/locale \
  installer/resources/eepsite.help/help \
  installer/resources/initialNews \
  installer/resources/proxy \
  installer/resources/readme \
  apps/routerconsole/jsp \
  apps/i2ptunnel/jsp \
  apps/susidns/src/jsp"

for i in `find $DIRS -maxdepth 1 -type f`
do
	echo "Checking $i ..."
	iconv -f UTF8 -t UTF8 $i -o /dev/null
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
done

echo "Checking all Java files ..."
for i in `find . -name \*.java -type f`
do
	#echo "Checking $i ..."
	iconv -f UTF8 -t UTF8 $i -o /dev/null
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
