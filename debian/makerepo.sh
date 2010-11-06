#!/bin/bash
# This script creates a Debian repository in ${DIR} using the reprepro tool.
# The packages are signed with the key referenced in the newest changelog entry.
#
# TODO: error handling

cd $(dirname $0)
DIR=./repo
CONFDIR=conf
CONFFILE=${CONFDIR}/distributions

SIGNER=`parsechangelog --file changelog | grep Maintainer | cut -d: -f2`
SIGNER=${SIGNER//^ /}
SIGNER=`echo ${SIGNER} | cut -d\  -f1`
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
reprepro --ask-passphrase --outdir ${DIR} includedeb all ../../i2p_*.deb
reprepro --ask-passphrase --outdir ${DIR} include all ../../i2p_*.changes

# export the public key
gpg --armor --export ${SIGNER} > ${DIR}/0x${KEYID}.asc

# remove the config file created above
echo Cleaning up...
rm -f ${CONFFILE}
rmdir ${CONFDIR}

echo Debian repository created in `pwd`/${DIR}.
