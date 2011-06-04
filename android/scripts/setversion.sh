#
#  Get the version number and fix up AndroidManifest.xml
#  Public domain
#
THISDIR=$(realpath $(dirname $(which $0)))
cd $THISDIR
MANIFEST=../AndroidManifest.xml
TMP=AndroidManifest.xml.tmp

CORE=`grep 'public final static String VERSION' ../../core/java/src/net/i2p/CoreVersion.java | \
         cut -d '"' -f 2`

MAJOR=`echo $CORE | cut -d '.' -f 1`
MINOR=`echo $CORE | cut -d '.' -f 2`
RELEASE=`echo $CORE | cut -d '.' -f 3`

ROUTERBUILD=$((`grep 'public final static long BUILD' ../../router/java/src/net/i2p/router/RouterVersion.java | \
         cut -d '=' -f 2 | \
         cut -d ';' -f 1`))

ANDROIDBUILD=`grep 'build.number' build.number | \
         cut -d '=' -f 2`

SDK=`grep 'android:minSdkVersion' $MANIFEST | \
         cut -d '"' -f 2`

# don't let build number get too long
VERSIONSTRING="${CORE}-${ROUTERBUILD}_b$(($ANDROIDBUILD % 256))-SDK$SDK"

#
# Android version code is an integer.
# So we have 31 bits.
# MAJOR	 	4 bits 0-15
# MINOR 	8 bits 0-255
# RELEASE	8 bits 0-255
# ROUTERBUILD	8 bits 0-255
# ANDROIDBUILD	3 bits 0-7
#
# Note that ANDROIDBUILD is modded % 8, it will wrap,
# beware of that if you release multiple builds using the
# same ROUTERBUILD, or clear it if you update ROUTERBUILD
#
VERSIONINT=$(( \
		(($MAJOR % 16) << 27) + \
		(($MINOR % 256) << 19) + \
		(($RELEASE % 256) << 11) + \
		(($ROUTERBUILD % 256) << 3) + \
		 ($ANDROIDBUILD % 8) \
	      ))

echo "Android version: '$VERSIONSTRING' (${VERSIONINT})"

SUBST='s/android.versionCode="[0-9]"/android.versionCode="'${VERSIONINT}'"/'
sed "$SUBST" < $MANIFEST > $TMP
SUBST='s/android.versionName="[^"]*"/android.versionName="'${VERSIONSTRING}'"/'
sed "$SUBST" < $TMP > $MANIFEST
rm -f $TMP
