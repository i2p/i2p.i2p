#
# Run 'certtool -i' on all certificate files
# Returns nonzero on failure
#
# zzz 2011-08
# public domain
#

cd `dirname $0`/../../installer/resources/certificates

for i in *
do
	echo "Checking $i ..."
	EXPIRES=`certtool -i < $i | grep 'Not After'`
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
	echo $EXPIRES
	# TODO - parse and fail if it expires soon
done

if [ "$FAIL" != "" ]
then
	echo "******** At least one file failed check *********"
else
	echo "All files passed"
fi
exit $FAIL
