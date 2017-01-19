#!/bin/sh

# Abort on error or unset variables
set -e
set -u

# This is changed by i2p.SlackBuild
INST_DIR=directory
PKGNAME="%pkg"

config() {
    NEW="$1"
    OLD="$(dirname $NEW)/$(basename $NEW .new)"
    if [ ! -r $NEW ]; then
        # If we get here there's a flaw in the packaging. We'll just return so that
        # we don't emit a spurious error for the user. (It's not the user's problem).
        return
    fi

    # If this file doesn't exist yet, drop the .new extension.
    if [ ! -r $OLD ]; then
        mv $NEW $OLD
        return
    elif [ "$(md5sum $OLD | cut -d' ' -f1)" = "$(md5sum $NEW | cut -d' ' -f1)" ]; then
        # If there are no differences in the files, remove the file with the .new extension.
        rm $NEW
        return
    fi
    # Alert the admin if they differ, but let's not be terribly obnoxious about it.
    echo "WARNING: The files $OLD and $NEW differ." >&2
}

# Unlike previous versions of the package, we install i2prouter and eepget to /usr/bin
# to make them available within the system PATH.

# Users might still want to /opt/i2p/i2prouter or /opt/i2p/eepget so we'll create symlinks
# in the installation directory.
ln -sf /usr/bin/eepget $INST_DIR
ln -sf /usr/bin/i2prouter $INST_DIR
(cd /usr/doc/$PKGNAME; ln -sf $INST_DIR/history.txt changelog)

if $(uname -m | grep -q '64'); then
    (cd $INST_DIR; ln -sf i2psvc-linux-x86-64 i2psvc)
else
    (cd $INST_DIR; ln -sf i2psvc-linux-x86-32 i2psvc)
fi

config /etc/rc.d/rc.i2p.new
config $INST_DIR/wrapper.config.new

if [ -e /var/log/packages/i2p-base* ]; then
    echo "Warning: This package supersedes the 'i2p-base' package." >&2
    echo
    echo "You may want to 'removepkg i2p-base'" >&2
    echo "and check the contents of /etc/rc.d/rc.local*" >&2
    echo "for correctness" >&2
fi

# Remove extraneous 'sh' from sponge's set-up
sed -i 's|sh /etc/rc\.d/rc\.i2p|/etc/rc.d/rc.i2p|g' /etc/rc.d/rc.local*
