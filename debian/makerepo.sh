#!/bin/sh
# This script creates a Debian repository in ${DIR} using the reprepro tool.
# The packages are signed with the key referenced in the newest changelog entry.

set -e

cd $(dirname $0)
DIR=./repo
CONFDIR=conf
CONFFILE=${CONFDIR}/distributions

SIGNER=`parsechangelog --file changelog | grep Maintainer | cut -d\< -f2 | cut -d\> -f1`
KEYID=`gpg --list-keys "${SIGNER}" | cut -d: -f2 | grep -w pub | cut -d/ -f2 | cut -d\  -f1`
echo Using signing key: ${SIGNER}
echo Key ID: ${KEYID}

# creating the reprepro config file dynamically allows us to specify the signer
mkdir -p ${CONFDIR}
echo "Origin: I2P" > ${CONFFILE}
echo "Label: I2P Debian Repository" >> ${CONFFILE}
echo "Suite: all" >> ${CONFFILE}
echo "Codename: all" >> ${CONFFILE}
echo "Architectures: i386 amd64 source" >> ${CONFFILE}
echo "Components: main" >> ${CONFFILE}
echo "SignWith: ${SIGNER}" >> ${CONFFILE}

# create the repository
echo Building the repository...
find packages/ -name *.deb -exec reprepro --ask-passphrase --outdir ${DIR} includedeb all {} \;
find packages/ -name *.changes -exec reprepro --ask-passphrase --outdir ${DIR} include all {} \;
find packages/ -name *.dsc -exec reprepro --ask-passphrase --outdir ${DIR} includedsc all {} \;

# export the public key
gpg --armor --export ${SIGNER} > ${DIR}/0x${KEYID}.asc

# remove the config file created above
echo Cleaning up...
rm -f ${CONFFILE}
rmdir ${CONFDIR}

echo Debian repository created in `pwd`/${DIR}.
