#!/bin/sh

export JAVA_HOME=/opt/jdk/jre

# Ensure user rights
chown -R i2p:nobody /opt/i2p
chmod -R u+rwx /opt/i2p

gosu i2p /opt/i2p/i2psvc /opt/i2p/wrapper.config wrapper.pidfile=/var/tmp/i2p.pid \
   wrapper.name=i2p \
   wrapper.displayname="I2P Service" \
   wrapper.statusfile=/var/tmp/i2p.status \
   wrapper.java.statusfile=/var/tmp/i2p.java.status \
   wrapper.logfile=/var/tmp/wrapper.log
