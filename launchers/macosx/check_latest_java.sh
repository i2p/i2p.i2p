#!/usr/bin/env bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SRCROOT=${SRCROOT:-"$DIR"}

echo "SRCROOT == $SRCROOT"

java_version=11
jsubv=28
randhash="55eed80b163941c8885ad9298e6d786a"
cookie_header="Cookie: oraclelicense=accept-securebackup-cookie"
url="http://download.oracle.com/otn-pub/java/jdk/${java_version}+${jsubv}/${randhash}/jdk-${java_version}_osx-x64_bin.dmg"

expected_etag="81ee08846975d4b8d46acf3b6eddf103:1531792451.574613"


curl -I -H '${cookie_header}' -v -L $url 2> .tmp-java-check.log
up_to_date="$(grep '${expected_etag}' .tmp-java-check.log | perl -pe 'chomp')"

if [ ! -z "${up_to_date}"]; then
    echo "NEW JAVA VERSION!"
    cat .tmp-java-check.log
    exit 1;
fi

echo "Java version check: Up to date!";

rm -f .tmp-java-check.log

header_content=$(cat <<EOF
#ifndef __META_DL_JAVA_H__
#define __META_DL_JAVA_H__

#define JAVA_DOWNLOAD_URL "$url"

#endif // __META_DL_JAVA_H__
EOF
)

echo $header_content > $SRCROOT/meta_dl_java.h && echo "Wrote url to file $SRCROOT/meta_dl_java.h"


