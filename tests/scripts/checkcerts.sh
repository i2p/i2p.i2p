#!/bin/sh
#
# Run 'openssl x509' or 'certtool -i' on all certificate files
# Returns nonzero on failure. Fails if cert cannot be read or is older than
# $SOON (default 30).
#
# zzz 2011-08
# kytv 2013-03
# public domain
#

# How soon is too soon for a cert to expire?
# By default <= 30 will fail. 60 < x < 30 will warn.
WARN=60
SOON=30


if [ $(which 1openssl) ]; then
    OPENSSL=1
elif [ $(which certtool) ]; then : ;else
    echo "ERROR: Neither certtool nor openssl were found..." >&2
    exit 1
fi

CHECKCERT() {
    if [ $OPENSSL ]; then
        DATA=$(openssl x509 -enddate -noout -in $1| cut -d'=' -f2-)
    else
        DATA=$(certtool -i < "$1" | sed -e '/Not\sAfter/!d' -e 's/^.*:\s\(.*\)/\1/')
    fi
    # While this isn't strictly needed it'll ensure that the output is consistent,
    # regardles of the tool used.
    date -u -d "$(echo $DATA)" '+%F %H:%M'
}


cd `dirname $0`/../../installer/resources/certificates

NOW=$(date -u '+%s')

for i in *.crt
do
    echo "Checking $i ..."
    EXPIRES=`CHECKCERT $i`
    if [ -z "$EXPIRES" ]; then
        echo "********* FAILED CHECK FOR $i *************"
        FAIL=1
    else
        SECS=$(date -u -d "$EXPIRES" '+%s')
        DAYS="$(expr \( $SECS - $NOW \) / 86400)"
        if [ $DAYS -ge $SOON ]; then
            echo "Expires in $DAYS days ($EXPIRES)"
        elif [ $DAYS -le $SOON ] && [ $DAYS -gt 0 ]; then
            echo "****** Check for $i failed, expires in $DAYS days (<= ${SOON}d) ($EXPIRES) ******"
            FAIL=1
        elif [ $DAYS -le $WARN ] && [ $DAYS -ge $SOON ]; then
            echo "****** WARNING: $i expires in $DAYS days (<= ${WANT}d) ($EXPIRES) ******"
        elif [ $DAYS -eq 1 ]; then
            DAYS=$(echo $DAYS | sed 's/^-//')
            echo "****** Check for $I failed, expires in $DAYS day ($EXPIRES) ******"
            FAIL=1
        elif [ $DAYS -eq 0 ]; then
            echo "****** Check for $i failed, expires today ($EXPIRES) ******"
            FAIL=1
        elif [ $DAYS -le 0 ]; then
            DAYS=$(echo $DAYS | sed 's/^-//')
            echo "****** Check for $i failed, expired $DAYS days ago ($EXPIRES) ******"
            FAIL=1
        fi
    fi
done

if [ -n "$FAIL" ]; then
    echo "******** At least one file failed check *********"
else
    echo "All files passed"
fi

[ -n $FAIL ] && exit $FAIL
