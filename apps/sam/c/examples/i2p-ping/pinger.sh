#!/bin/sh

# Copyright (c) 2004, Matthew P. Cashdollar <mpc@innographx.com>
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are
# met:
#
#     * Redistributions of source code must retain the above copyright
# notice, this list of conditions and the following disclaimer.
#     * Redistributions in binary form must reproduce the above copyright
# notice, this list of conditions and the following disclaimer in the
# documentation and/or other materials provided with the distribution.
#     * Neither the name of the author nor the names of any contributors
# may be used to endorse or promote products derived from this software
# without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
# IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
# TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
# PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
# OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
# EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
# PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
# PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
# LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
# NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
# SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

# This script I use to ping all the hosts in the I2P hosts.txt file.  You should
# edit your hosts.txt and remove all the comments and blanks lines before
# running this.
#
# You can set this up to run daily via cron.  It takes a very long time to run
# so I recommend starting it in the middle of the night.

OUTPUT=/tmp/index.html
FINALOUT=$HOME/www/index.html

echo "<html>" > $OUTPUT
echo "<head>" >> $OUTPUT
echo "<title>I2P Weather Report</title>" >> $OUTPUT
echo "</head>" >> $OUTPUT
echo "<body>" >> $OUTPUT
echo "<h1>I2P Weather Report</h1>" >> $OUTPUT
echo "Date: " >> $OUTPUT
date -u >> $OUTPUT
echo "(refreshed daily)" >> $OUTPUT
echo "<p>" >> $OUTPUT
echo "If your site isn't listed, that means the ping failed (I2P error)" >> $OUTPUT
echo "<p>" >> $OUTPUT
echo "<pre>" >> $OUTPUT
cut -d"=" -f1 $HOME/i2p/hosts.txt | tr "\n" " " | xargs $HOME/bin/i2p-ping -q -c 1 | grep -v "I2P error" >> $OUTPUT
echo "</pre>" >> $OUTPUT
echo "<p>" >> $OUTPUT
echo "<i>Disclaimer: This only indicates accessibility from my router, not the network in general.</i>" >> $OUTPUT
echo "</body>" >> $OUTPUT
echo "</html>" >> $OUTPUT
cp $OUTPUT $FINALOUT
