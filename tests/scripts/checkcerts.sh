#!/bin/sh
#
# Run 'openssl x509' or 'certtool -i' on all certificate files
# Returns nonzero on failure. Fails if cert cannot be read or is older than
# $SOON (default 30).
#
# Hard dependency: OpenSSL OR gnutls
# Recommended: GNU date
#
# zzz 2011-08
# kytv 2013-03
# public domain
#

# How soon is too soon for a cert to expire?
# By default <= 30 will fail. 60 < x < 30 will warn.
WARN=60
SOON=30


if [ $(which openssl) ]; then
    OPENSSL=1
elif [ $(which certtool) ]; then : ;else
    echo "ERROR: Neither certtool nor openssl were found..." >&2
    exit 1
fi

# This "grouping hack" is here to prevent errors from being displayed with the
# original Bourne shell (Linux shells don't need the {}s
if { date --help;} >/dev/null 2>&1 ; then
    HAVE_GNUDATE=1
fi

checkcert() {
    if [ $OPENSSL ]; then
        DATA=$(openssl x509 -enddate -noout -in $1| cut -d'=' -f2-)
    else
        DATA=$(certtool -i < "$1" | sed -e '/Not\sAfter/!d' -e 's/^.*:\s\(.*\)/\1/')
    fi
    # While this isn't strictly needed it'll ensure that the output is consistent,
    # regardles of the tool used. Dates/times are formatting according to OpenSSL's output
    # since this available by default on most systems.
    if [ -n "$HAVE_GNUDATE" ]; then
        LANG=C date -u -d "$(echo $DATA)" '+%b %d %H:%M:%S %Y GMT'
    else
        echo $DATA
    fi
}

compute_dates() {
    # Date computations currently depend on GNU date(1).
    # If run on a non-Linux system just print the expiration date.
    # TODO Cross-platform date calculation support
    if [ -n "$HAVE_GNUDATE" ]; then
        SECS=$(date -u -d "$EXPIRES" '+%s')
        DAYS="$(expr \( $SECS - $NOW \) / 86400)"
        if [ $DAYS -ge $SOON ]; then
            echo "Expires in $DAYS days ($EXPIRES)"
        elif [ $DAYS -eq 1 ]; then
            DAYS=$(echo $DAYS | sed 's/^-//')
            echo "****** Check for $I failed, expires tomorrow ($EXPIRES) ******"
            FAIL=1
        elif [ $DAYS -eq 0 ]; then
            echo "****** Check for $i failed, expires today ($EXPIRES) ******"
            FAIL=1
        elif [ $DAYS -le $SOON ] && [ $DAYS -gt 0 ]; then
            echo "****** Check for $i failed, expires in $DAYS days (<= ${SOON}d) ($EXPIRES) ******"
            FAIL=1
        elif [ $DAYS -lt $WARN ] && [ $DAYS -gt $SOON ]; then
            echo "****** WARNING: $i expires in $DAYS days (<= ${WANT}d) ($EXPIRES) ******"
        elif [ $DAYS -lt 0 ]; then
            DAYS=$(echo $DAYS | sed 's/^-//')
            echo "****** Check for $i failed, expired $DAYS days ago ($EXPIRES) ******"
            FAIL=1
        fi
    else
        echo $EXPIRES
    fi
}

cd `dirname $0`/../../installer/resources/certificates

NOW=$(date -u '+%s')

for i in *.crt
do
    echo "Checking $i ..."
    EXPIRES=`checkcert $i`
    if [ -z "$EXPIRES" ]; then
        echo "********* FAILED CHECK FOR $i *************"
        FAIL=1
    else
       compute_dates
    fi
done

if [ -n "$FAIL" ]; then
    echo "******** At least one file failed check *********"
else
    echo "All files passed"
fi

[ -n $FAIL ] && exit $FAIL
