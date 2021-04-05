#!/bin/sh

# Check scripts in the I2P source for validity by running with "sh -n
# $SCRIPTNAME". Optionally checks for bashisms if "checkbashisms" is installed.

# Exits 0 if no errors, non-zero otherwise


cd `dirname $0`/../..

# Only Bourne-compatible scripts should be in this list.
SCRIPTFILES="\
	./apps/desktopgui/bundle-messages.sh \
	./apps/i2psnark/java/bundle-messages.sh \
	./apps/i2psnark/launch-i2psnark \
	./apps/i2ptunnel/java/bundle-messages*.sh \
	./apps/ministreaming/java/bundle-messages.sh \
	./apps/routerconsole/java/bundle-messages*.sh \
	./apps/sam/c/examples/i2p-ping/pinger.sh \
	./apps/susidns/src/bundle-messages.sh \
	./apps/susimail/bundle-messages.sh \
	./core/c/*.sh \
	./core/c/jbigi/*.sh \
	./debian/*.config \
	./debian/*.init \
	./debian/*.preinst \
	./debian/*.postinst \
	./debian/*.postrm \
	./installer/resources/*.sh \
	./installer/resources/eepget \
	./installer/resources/i2prouter \
	./installer/resources/install_i2p_service_osx.command \
	./installer/resources/install_i2p_service_unix \
	./installer/resources/locale/bundle-messages.sh \
	./installer/resources/uninstall_i2p_service_osx.command
	./installer/resources/uninstall_i2p_service_unix \
	./Slackware/i2p/i2p.SlackBuild \
	./Slackware/i2p/doinst.sh \
	./Slackware/i2p/rc.i2p \
	./tests/scripts/*.sh \
"

echo "Checking scripts for bashisms ..."
for script in $SCRIPTFILES; do
    #echo "Checking $script ..."
    if sh -n "$script" ; then : ; else
        echo "********* FAILED CHECK FOR $script *************"
        FAIL=1
    fi
    if $(which checkbashisms > /dev/null 2>&1) ; then
        checkbashisms $script
    fi
done

if [ "$FAIL" != "" ]
then
    echo "******** At least one file failed check *********"
else
    echo "All files passed"
fi
exit $FAIL
