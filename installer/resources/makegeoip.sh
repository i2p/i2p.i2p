#!/bin/sh
#
# Fetch the latest file from Maxmind,
# and pull out what we need
#
rm -rf GeoLite2-Country_20*
mv GeoLite2-Country.tar.gz GeoIPCountry2-Country.tar.gz.bak
mv GeoLite2-Country.mmdb.gz GeoIPCountry2-Country.mmdb.gz.bak
wget http://geolite.maxmind.com/download/geoip/database/GeoLite2-Country.tar.gz || exit 1
tar xzf GeoLite2-Country.tar.gz || exit 1
mv GeoLite2-Country_20*/GeoLite2-Country.mmdb . || exit 1
gzip GeoLite2-Country.mmdb || exit 1
rm -rf GeoLite2-Country_20*
ls -l GeoLite2-Country.mmdb.gz
