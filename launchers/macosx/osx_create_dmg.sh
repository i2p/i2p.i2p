#!/usr/bin/env bash
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

source $DIR/.sign-secrets

APP_NAME="I2PLauncher"
VERSION="`/usr/libexec/PlistBuddy -c 'Print I2PRouterVersion' Info.plist`"
DMG_BACKGROUND_IMG=${BACKGROUND_IMG:-"Background.png"}

APP_EXE="${APP_NAME}.app/Contents/MacOS/${APP_NAME}"
VOL_NAME="${APP_NAME}-${VERSION}"
DMG_TMP="${VOL_NAME}-temp.dmg"
DMG_FINAL="${DMG_NAME:-"I2PMacLauncher-fallback"}.dmg"
STAGING_DIR="/tmp/mkdmg$$"

# Check the background image DPI and convert it if it isn't 72x72
_BACKGROUND_IMAGE_DPI_H=`sips -g dpiHeight ${DMG_BACKGROUND_IMG} | grep -Eo '[0-9]+\.[0-9]+'`
_BACKGROUND_IMAGE_DPI_W=`sips -g dpiWidth ${DMG_BACKGROUND_IMG} | grep -Eo '[0-9]+\.[0-9]+'`

if [ $(echo " $_BACKGROUND_IMAGE_DPI_H != 72.0 " | bc) -eq 1 -o $(echo " $_BACKGROUND_IMAGE_DPI_W != 72.0 " | bc) -eq 1 ]; then
   echo "WARNING: The background image's DPI is not 72.  This will result in distorted backgrounds on Mac OS X 10.7+."
   echo "         I will convert it to 72 DPI for you."

   _DMG_BACKGROUND_TMP="${DMG_BACKGROUND_IMG%.*}"_dpifix."${DMG_BACKGROUND_IMG##*.}"

   sips -s dpiWidth 72 -s dpiHeight 72 $DIR/${DMG_BACKGROUND_IMG} --out $DIR/${_DMG_BACKGROUND_TMP}


  DMG_BACKGROUND_IMG="${_DMG_BACKGROUND_TMP}"
fi

# copy over the stuff we want in the final disk image to our staging dir
mkdir -p "${STAGING_DIR}"
cp -Rpf "${APP_NAME}.app" "${STAGING_DIR}"
# ... cp anything else you want in the DMG - documentation, etc.

# figure out how big our DMG needs to be
#  assumes our contents are at least 1M!
SIZE=`du -sh "${STAGING_DIR}" | sed 's/\([0-9\.]*\)M\(.*\)/\1/'`
SIZE=`echo "${SIZE} + 23.0" | bc | awk '{print int($1+0.5)}'`

if [ $? -ne 0 ]; then
   echo "Error: Cannot compute size of staging dir"
   exit
fi

# create the temp DMG file
hdiutil create -srcfolder "${STAGING_DIR}" -volname "${VOL_NAME}" -fs HFS+ \
      -fsargs "-c c=64,a=16,e=16" -format UDRW -size ${SIZE}M "$RELEASE_DIR/${DMG_TMP}"

echo "Created DMG: ${DMG_TMP}"

# mount it and save the device
DEVICE=$(hdiutil attach -readwrite -noverify "$RELEASE_DIR/${DMG_TMP}" | \
         egrep '^/dev/' | sed 1q | awk '{print $1}')

sleep 2



# add a link to the Applications dir
echo "Add link to /Applications"
cd /Volumes/"${VOL_NAME}"
ln -sf /Applications Applications

# add a background image
mkdir -p /Volumes/"${VOL_NAME}"/.background
cp "$DIR/`basename ${DMG_BACKGROUND_IMG}`" /Volumes/"${VOL_NAME}"/.background/`basename ${DMG_BACKGROUND_IMG}`

cd $RELEASE_DIR

# tell the Finder to resize the window, set the background,
#  change the icon size, place the icons in the right position, etc.
echo '
   tell application "Finder"
     tell disk "'${VOL_NAME}'"
           open
           set current view of container window to icon view
           set toolbar visible of container window to false
           set statusbar visible of container window to false
           set the bounds of container window to {400, 100, 920, 440}
           set viewOptions to the icon view options of container window
           set arrangement of viewOptions to not arranged
           set icon size of viewOptions to 72
           set background picture of viewOptions to file ".background:'${DMG_BACKGROUND_IMG}'"
           set position of item "'${APP_NAME}'.app" of container window to {160, 205}
           set position of item "Applications" of container window to {360, 205}
           close
           open
           update without registering applications
           delay 2
     end tell
   end tell
' | osascript

sync

# unmount it
hdiutil detach "${DEVICE}"

# now make the final image a compressed disk image
echo "Creating compressed image"
hdiutil convert "$RELEASE_DIR/${DMG_TMP}" -format UDZO -imagekey zlib-level=9 -o "$RELEASE_DIR/${DMG_FINAL}"

codesign --force --deep --sign "${APPLE_CODE_SIGNER_ID}" "$RELEASE_DIR/${DMG_FINAL}"

# clean up
rm -rf "$RELEASE_DIR/${DMG_TMP}"
rm -rf "${STAGING_DIR}"


echo 'Done.'

exit
