#!/bin/sh
#
# Fetch the latest file from Maxmind,
# and pull out what we need
#
mv GeoIPCountryCSV.zip GeoIPCountryCSV.zip.bak
mv GeoIPCountryWhois.csv GeoIPCountryWhois.csv.bak
wget http://geolite.maxmind.com/download/geoip/database/GeoIPCountryCSV.zip
unzip GeoIPCountryCSV.zip
echo 'old entry count'
wc -l geoip.txt
GDATE=`ls -l --time-style=long-iso GeoIPCountryCSV.zip | cut -d ' ' -f 6`
echo '# Last updated based on Maxmind GeoLite Country' > geoip.txt
echo "# dated $GDATE" >> geoip.txt
cat << EOF >> geoip.txt
# Script borrowed from Tor
#
# wget http://geolite.maxmind.com/download/geoip/database/GeoIPCountryCSV.zip
# unzip GeoIPCountryCSV.zip
# cut -d, -f3-5 < GeoIPCountryWhois.csv|sed 's/"//g' > geoip.txt
# cut -d, -f5,6 < GeoIPCountryWhois.csv |sed 's/"//g' | sort | uniq > countries.txt
EOF
cut -d, -f3-5 < GeoIPCountryWhois.csv|sed 's/"//g' >> geoip.txt
echo 'new entry count'
wc -l geoip.txt
# cut -d, -f5,6 < GeoIPCountryWhois.csv |sed 's/"//g' | sort | uniq > countries.txt
