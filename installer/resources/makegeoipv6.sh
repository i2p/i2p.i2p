#!/bin/sh
#
# Fetch the latest file from Maxmind, merge with
# our additions, and compress.
#

FILE1=GeoIPv6.csv.gz
FILE2=geoipv6-extras.csv
FILEOUT=geoipv6.dat.gz

rm -f $FILE1 $FILEOUT
wget http://geolite.maxmind.com/download/geoip/database/$FILE1
if [ "$?" -ne "0" ]
then
	echo 'Cannot fetch'
	exit 1
fi
java -cp ../../build/i2p.jar:../../build/router.jar net.i2p.router.transport.GeoIPv6 $FILE1 $FILE2 $FILEOUT
exit $?
