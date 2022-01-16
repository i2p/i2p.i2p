#!/usr/bin/with-contenv sh

#
# Add the app user to the password and group databases.  This is needed just to
# make sure that mapping between the user/group ID and its name is possible.
#

set -e # Exit immediately if a command exits with a non-zero status.
set -u # Treat unset variables as an error.

cp /defaults/passwd /etc/passwd
cp /defaults/group /etc/group
cp /defaults/shadow /etc/shadow
chown root:shadow /etc/shadow
chmod 640 /etc/shadow

echo "$APP_USER:x:$USER_ID:$GROUP_ID::${APP_HOME:-/dev/null}:/sbin/nologin" >> /etc/passwd
echo "$APP_USER:x:$GROUP_ID:" >> /etc/group

# Make sure APP_HOME is editable by the user
if [[ -n "$APP_HOME" ]] ; then
    chown -R "$APP_USER" "$APP_HOME" || true
    chmod -R u+rw "$APP_HOME" || true
fi

# vim:ft=sh:ts=4:sw=4:et:sts=4

