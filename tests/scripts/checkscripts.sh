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
	./apps/i2ptunnel/java/bundle-messages-proxy.sh \
	./apps/i2ptunnel/java/bundle-messages.sh \
	./apps/routerconsole/java/bundle-messages-news.sh \
	./apps/routerconsole/java/bundle-messages.sh \
	./apps/sam/c/examples/i2p-ping/pinger.sh \
	./apps/susidns/src/bundle-messages.sh \
	./apps/susimail/bundle-messages.sh \
	./core/c/build.sh \
	./core/c/jbigi/build-all.sh \
	./core/c/jbigi/build_jbigi.sh \
	./core/c/jbigi/build.sh \
	./core/c/jbigi/mbuild-all.sh \
	./core/c/jcpuid/build.sh \
	./core/c/mbuild.sh \
	./debian/i2p.config \
	./debian/i2p-doc.preinst \
	./debian/i2p.init \
	./debian/i2p.postinst \
	./debian/i2p.postrm \
	./debian/i2p.preinst \
	./debian/libjbigi-jni.preinst \
	./debian/repack.sh \
	./installer/resources/install_i2p_service_osx.command \
	./installer/resources/install_i2p_service_unix \
	./installer/resources/locale/bundle-messages.sh \
	./installer/resources/makegeoipv6.sh \
	./installer/resources/postinstall.sh \
	./installer/resources/runplain.sh \
	./installer/resources/uninstall_i2p_service_osx.command
	./installer/resources/uninstall_i2p_service_unix \
	./Slackware/i2p/i2p.SlackBuild \
	./Slackware/i2p/doinst.sh \
	./Slackware/i2p/rc.i2p \
	./tests/scripts/checkcerts.sh \
	./tests/scripts/checkpo.sh \
	./tests/scripts/checkutf8.sh \
	./tests/scripts/checkxml.sh \
	./tests/scripts/testjbigi.sh \
"

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
