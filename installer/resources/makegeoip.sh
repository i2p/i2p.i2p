#!/bin/sh
#
# Fetch the latest file from db-ip.com
#
rm -f dbip-country-lite-*
VER=`date +%Y-%m`
DL=dbip-country-lite-${VER}.mmdb.gz
FILE=GeoLite2-Country.mmdb.gz
wget https://download.db-ip.com/free/$DL || exit 1
mv $FILE ${FILE}.bak
mv $DL $FILE
ls -l $FILE
OLD_HASH=$(sha256sum ./installer/resources/$FILE | cut -f 1 -d ' ')
NEW_HASH=$(sha256sum ./$FILE | cut -f 1 -d ' ')
echo $OLD_HASH $NEW_HASH
if [ $OLD_HASH != $NEW_HASH ]; then
    cp -v "$FILE" "./installer/resources/$FILE"
fi
