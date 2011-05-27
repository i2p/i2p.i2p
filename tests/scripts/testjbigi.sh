#
# Runs a test using each libjbigi-linux-*.so file
# Returns nonzero on failure, but it will always
# pass because NBI doesnt return an error code (yet).
# But when it does, it will fail all the time since
# your hardware probably can't run all versions.
#
# zzz 2011-05
# public domain
#

cd `dirname $0`/../../installer/lib/jbigi

TMP=/tmp/testjbigi$$
mkdir $TMP
for i in libjbigi-linux-*.so
do
	echo "Testing $i ..."
	ln -s $PWD/$i $TMP/libjbigi.so
        java -cp ../../../build/i2p.jar -Djava.library.path=$TMP net.i2p.util.NativeBigInteger | \
             egrep 'java|native|However'
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
	rm $TMP/libjbigi.so
	echo
done

if [ "$FAIL" != "" ]
then
	echo "******** At least one file failed check *********"
else
	echo "All files passed"
fi
rm -rf $TMP
exit $FAIL
