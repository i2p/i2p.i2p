#
# Update messages_xx.po and messages_xx.class files,
# from both java and jsp sources.
# Requires installed programs xgettext, msgfmt, msgmerge, and find.
# zzz - public domain
#

## launching sh.exe with -login parameter will open a shell with the current path always pointing to \bin\
## need to cd into our orignal path - where we call sh.exe from.

cd $CALLFROM
echo $PWD

## except this everything is the same with bundle-message.sh
## walking - public domain :-D

source bundle-messages.sh