#!/bin/sh
#
# Validate XML and HTML files using xmllint
# Returns nonzero on failure
#
# Note that the news.xml and initialNews*.xml files are really HTML
#
# zzz 2011-03
# public domain
#

cd `dirname $0`/../..

XMLFILES="\
./apps/addressbook/build.xml \
./apps/BOB/build.xml \
./apps/BOB/Demos/echo/echoclient/build.xml \
./apps/BOB/Demos/echo/echoclient/nbproject/build-impl.xml \
./apps/BOB/Demos/echo/echoclient/nbproject/private/private.xml \
./apps/BOB/Demos/echo/echoclient/nbproject/project.xml \
./apps/BOB/Demos/echo/echoserver/build.xml \
./apps/BOB/Demos/echo/echoserver/nbproject/build-impl.xml \
./apps/BOB/Demos/echo/echoserver/nbproject/private/private.xml \
./apps/BOB/Demos/echo/echoserver/nbproject/project.xml \
./apps/BOB/nbproject/build-impl.xml \
./apps/BOB/nbproject/project.xml \
./apps/desktopgui/build.xml \
./apps/i2psnark/java/build.xml \
./apps/i2psnark/jetty-i2psnark.xml \
./apps/i2psnark/web.xml \
./apps/i2ptunnel/java/build.xml \
./apps/i2ptunnel/jsp/web.xml \
./apps/jetty/build.xml \
./apps/ministreaming/java/build.xml \
./apps/routerconsole/java/build.xml \
./apps/routerconsole/jsp/web.xml \
./apps/sam/java/build.xml \
./apps/streaming/java/build.xml \
./apps/susidns/src/build.xml \
./apps/susidns/src/WEB-INF/web-template.xml \
./apps/susimail/build.xml \
./apps/susimail/src/WEB-INF/web.xml \
./apps/systray/java/build.xml \
./build.xml \
./core/java/build.xml \
./core/java/nbproject/project.xml \
./installer/i2pinstaller.xml \
./installer/i2pstandalone.xml \
./installer/install.xml \
./installer/resources/eepsite/jetty-ssl.xml
./installer/resources/eepsite/contexts/cgi-context.xml
./installer/resources/eepsite/contexts/base-context.xml
./installer/resources/eepsite/jetty-rewrite.xml
./installer/resources/eepsite/etc/webdefault.xml
./installer/resources/eepsite/jetty-jmx.xml
./installer/resources/eepsite/jetty.xml
./installer/resources/shortcutSpec.xml \
./router/java/build.xml \
./Slackware/i2p/build.xml"

HTMLFILES="\
./installer/resources/initialNews/*.xml \
./apps/BOB/src/net/i2p/BOB/package.html \
./apps/desktopgui/src/net/i2p/desktopgui/package.html \
./apps/ministreaming/java/src/net/i2p/client/streaming/package.html \
./apps/susimail/src/index.html \
./core/java/src/net/i2p/client/datagram/package.html \
./core/java/src/net/i2p/client/naming/package.html \
./core/java/src/net/i2p/client/package.html \
./core/java/src/net/i2p/crypto/package.html \
./core/java/src/net/i2p/data/i2cp/package.html \
./core/java/src/net/i2p/data/package.html \
./core/java/src/net/i2p/internal/package.html \
./core/java/src/net/i2p/package.html \
./core/java/src/net/i2p/stat/package.html \
./core/java/src/net/i2p/time/package.html \
./core/java/src/net/i2p/util/package.html \
./installer/resources/eepsite/docroot/help/index_de.html \
./installer/resources/eepsite/docroot/help/index_fr.html \
./installer/resources/eepsite/docroot/help/index.html \
./installer/resources/eepsite/docroot/help/index_na.html \
./installer/resources/eepsite/docroot/help/index_nl.html \
./installer/resources/eepsite/docroot/help/index_ru.html \
./installer/resources/eepsite/docroot/help/index_sv.html \
./installer/resources/eepsite/docroot/help/pagetemplate.html \
./installer/resources/eepsite/docroot/index.html \
./installer/resources/readme/readme_ar.html \
./installer/resources/readme/readme_de.html \
./installer/resources/readme/readme_es.html \
./installer/resources/readme/readme_fr.html \
./installer/resources/readme/readme.html \
./installer/resources/readme/readme_nl.html \
./installer/resources/readme/readme_pt.html \
./installer/resources/readme/readme_ru.html \
./installer/resources/readme/readme_sv.html \
./installer/resources/readme/readme_zh.html \
./installer/resources/small/toolbar.html \
./installer/resources/startconsole.html \
./router/java/src/net/i2p/data/i2np/package.html \
./router/java/src/net/i2p/router/package.html \
./router/java/src/net/i2p/router/peermanager/package.html \
./router/java/src/net/i2p/router/startup/package.html \
./router/java/src/net/i2p/router/transport/ntcp/package.html \
./router/java/src/net/i2p/router/transport/package.html \
./router/java/src/net/i2p/router/transport/udp/package.html \
./router/java/src/net/i2p/router/util/package.html"

echo 'Checking XML files....................'
for i in $XMLFILES
do
	#echo "Checking $i ..."
	xmllint --noout $i
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
done

echo 'Checking HTML files....................'
for i in $HTMLFILES
do
	#echo "Checking $i ..."
	xmllint --html --noout $i
	# FIXME html mode never exits with an error code
	# ... but it does output errors
        if [ $? -ne 0 ]
	then
		echo "********* FAILED CHECK FOR $i *************"
		FAIL=1
	fi
done

if [ "$FAIL" != "" ]
then
	echo "******** At least one file failed check *********"
else
	echo "All files passed"
fi
exit $FAIL
