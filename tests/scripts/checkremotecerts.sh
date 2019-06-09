#!/bin/sh
set -e
set -u

BASEDIR="$(dirname $0)/../../"
cd "$BASEDIR"
RESEEDHOSTS=$(sed -e '/^\s\+"https:\/\/[-a-z0-9.]/!d' -e 's/.*"https:\/\/\([-a-z0-9.:]\+\).*/\1/' router/java/src/net/i2p/router/networkdb/reseed/Reseeder.java)
CERTHOME="installer/resources/certificates"
CACERTS=$(mktemp)
WORK=$(mktemp -d)
FAIL=0
MAX=5
OPENSSL=0
CERTTOOL=0

check_for_prog() {
    if which $1 > /dev/null 2>&1 ; then
        return 0
    else
        return 1
    fi
}

if pidof /usr/bin/tor > /dev/null 2>&1 && check_for_prog torsocks; then
    echo "-- Detected Tor, will try using it --"
    GNUTLS_BIN="torsocks gnutls-cli"
    OPENSSL_BIN="torsocks openssl"
else
    GNUTLS_BIN="gnutls-cli"
    OPENSSL_BIN="openssl"
fi

if check_for_prog certtool; then
    CERTTOOL=1
    echo "-- Checking certificates with GnuTLS --"
elif check_for_prog openssl; then
    OPENSSL=1
    echo "-- Checking certificates with OpenSSL --"
fi

if [ $CERTTOOL -ne 1 ] && [ $OPENSSL -ne 1 ]; then
    echo "ERROR: This script requires either gnutls or openssl" >&2
    exit
fi

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
        echo "try $i of $MAX failed for command $@" >&2
        if [ $i -ge $MAX ]
        then
            break
        fi
        i=$(expr $i + 1)
        sleep 15
    done
    if [ $i = $MAX ]; then
        return 1
    fi
}

normalize(){
    # Convert fingerprint to the format output by GnuTLS
    sed -e 's/^.*=//;s/://g;y/ABCDEF/abcdef/'
}

connect() {
    if [ $OPENSSL -eq 1 ]; then
        $OPENSSL_BIN s_client -connect "$1:$2" -CAfile $CACERTS -servername $1 < /dev/null 2> /dev/null
    else
        $GNUTLS_BIN --insecure --print-cert --x509cafile "$CACERTS" "$1" -p "$2"  < /dev/null 2>/dev/null
    fi
}

extract_finger() {
    if [ $CERTTOOL -eq 1 ]; then
        # Roughly equivalent to "grep -A1 "SHA-1 fingerprint" | head -n 2 | grep -o '[a-f0-9]{40}'"
        certtool -i < $1 | sed -n '/SHA-1 fingerprint/{n;p;q}' | sed 's/\s\+\([a-f0-9]\{40\}\)/\1/'
    else
        openssl x509 -in $1 -fingerprint -noout | normalize
    fi
}

verify_fingerprint() {
    if [ -e "$CERTHOME/ssl/$1.crt" ]; then
        EXPECTED=$(extract_finger "$CERTHOME/ssl/$1.crt")
        FOUND=$(extract_finger "$WORK/$1")
        if [ "$EXPECTED" != "$FOUND" ]; then
            echo -n "invalid certificate. Expected $EXPECTED, got $FOUND"
            FAIL=1
            echo $HOST >> $WORK/bad
        fi
    else
        echo "Untrusted certficate and certificate not found at $CERTHOME/ssl" >&2
        FAIL=1
        echo $HOST >> $WORK/bad
    fi
}

cleanup() {
    rm -rf $CACERTS $WORK
    exit $FAIL
}

check_hosts() {
    for HOST in $RESEEDHOSTS; do
        if $(echo $HOST | grep -q ':'); then
            OLDIFS=$IFS
            IFS=":"
            set -- $HOST
            HOSTNAM=$1
            PORT=$2
            IFS=$OLDIFS
        else
            HOSTNAM=$HOST
            PORT=443
        fi

        echo -n "Checking $HOSTNAM:$PORT..."
        if retry connect "$HOSTNAM" "$PORT"  < /dev/null 1> "$WORK/$HOST"; then

            # OpenSSL returns "return code: 0 (ok)"
            # GnuTLS returns "certificate is trusted"
            # GnuTLS v2 has the word "Peer" before certificate, v3 has the word "The" before it
            if ! grep -q 'Verify return code: 0 (ok)\|certificate is trusted' "$WORK/$HOST"; then
                # If we end up here, it's possible that the certificate is valid, but CA: false is set in the certificate.
                # The OpenSSL binary is "picky" about this. GnuTLS doesn't seem to be.
                verify_fingerprint $HOST
            fi
            echo
        else
            echo "failed to connect to $HOST" >&2
            FAIL=1
        fi
    done
}

assemble_ca
check_hosts
cleanup

