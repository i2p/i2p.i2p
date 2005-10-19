This is an I2P port of snark [http://klomp.org/snark], a GPL'ed bittorrent client

The build in tracker has been removed for simplicity.

Example usage:
  java -jar lib/i2psnark.jar myFile.torrent

or, a more verbose setting:
  java -jar lib/i2psnark.jar --eepproxy 127.0.0.1 4444 \
       --i2cp 127.0.0.1 7654 "inbound.length=2 outbound.length=2" \
       --debug 6 myFile.torrent
