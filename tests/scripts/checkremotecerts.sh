#!/bin/sh
set -e
set -u

if ! which openssl > /dev/null 2>&1 || ! which gnutls-cli > /dev/null 2>&1; then
    echo "This script (currently) requires both gnutls and openssl" >&2
    exit
fi

if pidof /usr/bin/tor > /dev/null 2>&1 && which torify > /dev/null 2>&1; then
    echo "-- Detected Tor, will try using it --"
    GNUTLS="torify gnutls-cli"
else
    GNUTLS="gnutls-cli"
fi

BASEDIR="$(dirname $0)/../../"
cd "$BASEDIR"
RESEEDHOSTS=$(sed -e '/\s\+"https:\/\/[-a-z0-9.]/!d' -e 's/.*"https:\/\/\([-a-z0-9.]\+\).*/\1/' router/java/src/net/i2p/router/networkdb/reseed/Reseeder.java)
CACERTS=$(mktemp)
WORK=$(mktemp -d)
FAIL=0
CERTHOME="installer/resources/certificates"


assemble_ca() {
    # Combine system certificates with the certificates shipped with I2P into
    # a large CA file for use with gnutls-cli later
    cat /etc/ssl/certs/ca-certificates.crt "$CERTHOME"/*/*.crt > "$CACERTS"
}

retry ()
# retry function borrowed from zzz's sync-mtn script
{
    if [ $# -eq 0 ]
    then
        echo 'usage: $0 command args...'
        exit 1
    fi

    i=1
    while ! "$@"
    do
        echo "$0: try $i of $MAX failed for command $@"
        if [ $i -ge $MAX ]
        then
            break
        fi
        i=$(expr $i + 1)
        sleep 15
    done
}

normalize(){
    # The format displayed by gnutls-cli. This function is not used yet.
    sed -e 's/^.*=//;s/://g;y/ABCDEF/abcdef/'
}

check() {
for HOST in $RESEEDHOSTS; do
    echo -n "Checking $HOST..."
    # Using --insecure here for those cases in which our
    retry $GNUTLS --insecure --print-cert --x509cafile="$CACERTS" "$HOST"  < /dev/null > "$WORK/$HOST.test"
    if $(grep -q 'The certificate issuer is unknown' "$WORK/$HOST.test"); then
        # If we end up here it's for one of two probable reasons:
        # 1) the the CN in the certificate doesn't match the hostname.
        # 2) the certificate is invalid
        openssl x509 -in "$CERTHOME/ssl/$HOST.crt" -fingerprint -noout > "$WORK/$HOST.expected.finger"
        openssl x509 -in "$WORK/$HOST.test" -fingerprint -noout > "$WORK/$HOST.real.finger"
        if [ "$(cat "$WORK/$HOST.expected.finger")" != "$(cat "$WORK/$HOST.real.finger")" ]; then
            echo -n "invalid certificate for $HOST"
            FAIL=1
            echo $HOST >> $WORK/bad
        fi
    fi
    echo
done
}

assemble_ca
check

rm -rf $CACERTS $WORK
exit $FAIL
