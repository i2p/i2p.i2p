#!/bin/sh
#
# Run 'openssl x509' or 'certtool -i' on all certificate files
# Returns nonzero on failure. Fails if cert cannot be read or is older than
# $SOON (default 60).
#
# Hard dependency: OpenSSL OR gnutls
# Recommended: GNU date
#
# zzz 2011-08
# kytv 2013-03
# public domain
#

# How soon is too soon for a cert to expire?
# By default <= 60 will fail. 90 < x < 60 will warn.
WARN=90
SOON=10


date2julian() {
    # Julian date conversion adapted from a post (its code released into the public
    # domain) by Tapani Tarvainen to comp.unix.shell 1998)) for portability
    # (e.g. using 'expr' instead of requiring Bash, ksh, or zsh).
    #   $1 = Month
    #   $2 = Day
    #   $3 = Year

    if [ "${1}" != "" ] && [ "${2}" != ""  ] && [ "${3}" != "" ]; then
        ## Because leap years add a day at the end of February,
        ## calculations are done from 1 March 0000 (a fictional year)
        d2j_tmpmonth=$(expr 12 \* $3 + $1 - 3)

        ## If it is not yet March, the year is changed to the previous year
        d2j_tmpyear=$(expr ${d2j_tmpmonth} / 12)

        ## The number of days from 1 March 0000 is calculated
        ## and the number of days from 1 Jan. 4713BC is added
        expr \( 734 \* ${d2j_tmpmonth} + 15 \) / 24 - 2 \* ${d2j_tmpyear} + ${d2j_tmpyear} / 4 - ${d2j_tmpyear} / 100 + ${d2j_tmpyear} / 400 + $2 + 1721119
    else
        # We *really* shouldn't get here
        echo 0
    fi
}

getmonth() {
    case ${1} in
        Jan)
            echo 1
            ;;
        Feb)
            echo 2
            ;;
        Mar)
            echo 3
            ;;
        Apr)
            echo 4
            ;;
        May)
            echo 5
            ;;
        Jun)
            echo 6
            ;;
        Jul)
            echo 7
            ;;
        Aug)
            echo 8
            ;;
        Sep)
            echo 9
            ;;
        Oct)
            echo 10
            ;;
        Nov)
            echo 11
            ;;
        Dec)
            echo 12
            ;;
          *)
            echo  0
            ;;
    esac
}

checkcert() {
    if [ $OPENSSL ]; then
        # OpenSSL's format: Mar  7 16:08:35 2022 GMT
        DATA=$(openssl x509 -enddate -noout -in $1 | cut -d'=' -f2-)
    else
        # Certtool's format: Mon Mar 07 16:08:35 UTC 2022
        DATA=$(certtool -i < "$1" | sed -e '/Not\sAfter/!d' -e 's/^.*:\s\(.*\)/\1/')
        # The formatting is normalized for passing to the date2julian function (if needed)
        set -- $DATA
        DATA="$2 $3 $4 $6 GMT"
    fi
    echo $DATA
}

get_bits() {
    if [ $OPENSSL ]; then
        BITS=$(openssl x509 -text -noout -in $1 | sed -e '/Public-Key/!d' \
            -e 's/\s\+Public-Key: (\([0-9]\+\) bit)/\1 bits/')
    else
        BITS=$(certtool -i < $1 | sed -e '/^.*Algorithm Security Level/!d' \
            -e 's/.*(\([0-9]\+\) bits).*/\1 bits/')
    fi
}

get_sigtype() {
    if [ $OPENSSL ]; then
        TYPE=$(openssl x509 -text -noout -in $1 | sed -e '/Signature Algorithm/!d' \
            -e 's/\s\+Signature Algorithm:\s\+\(.\+\)/\1/' | head -n1)
    else
        TYPE=$(certtool -i < $1 | sed -e '/^.*Signature Algorithm:/!d' \
            -e 's/.*:\s\+\(.*\)/\1/')
    fi
}

print_status() {
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
        elif [ $DAYS -le $WARN ] && [ $DAYS -gt $SOON ]; then
            echo "****** WARNING: $i expires in $DAYS days (<= ${WANT}d) ($EXPIRES) ******"
        elif [ $DAYS -lt 0 ]; then
            DAYS=$(echo $DAYS | sed 's/^-//')
            echo "****** Check for $i failed, expired $DAYS days ago ($EXPIRES) ******"
            FAIL=1
        fi
}

compute_dates() {
    if [ -n "$HAVE_GNUDATE" ]; then
        SECS=$(date -u -d "$EXPIRES" '+%s')
        DAYS="$(expr \( $SECS - $NOW \) / 86400)"
    else
        set -- $EXPIRES
        # date2julian needs the format mm dd yyyy
        SECS=$(date2julian `getmonth $1` $2 $4)
        DAYS=$(expr $SECS - $NOW)
    fi
    print_status
}

# This "grouping hack" is here to prevent errors from being displayed with the
# original Bourne shell (Linux shells don't need the {}s
if { date --help;} >/dev/null 2>&1 ; then
    HAVE_GNUDATE=1
    NOW=$(date -u '+%s')
else
    NOW=$(date2julian `date -u '+%m %d %Y'`)
fi

if [ $(which openssl) ]; then
    OPENSSL=1
elif [ $(which certtool) ]; then : ;else
    echo "ERROR: Neither certtool nor openssl were found..." >&2
    exit 1
fi

cd `dirname $0`/../../installer/resources/certificates

for i in */*.crt
do
    echo "Checking $i ..."
    EXPIRES=`checkcert $i`
    if [ -z "$EXPIRES" ]; then
        echo "********* FAILED CHECK FOR $i *************"
        FAIL=1
    else
       compute_dates
    fi
    get_bits $i && get_sigtype $i
    printf '%s - %s\n\n' "$BITS" "$TYPE"
    if grep '\s$' $i > /dev/null 2>&1; then
        echo "********* Trailing whitespace found in file $i *********"
        FAIL=1
    fi
    if grep '^\s' $i > /dev/null 2>&1; then
        echo "********* Leading whitespace found in file $i *********"
        FAIL=1
    fi
done

if [ -n "$FAIL" ]; then
    echo "******** At least one file failed check *********"
else
    echo "All files passed"
fi

[ -n $FAIL ] && exit $FAIL
