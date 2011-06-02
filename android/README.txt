These instructions are for a recent Android SDK (1.6 or later)..
Should also still work with a 1.5 SDK.
The build file is not compatible with the 1.1 SDK any more.

#Download the SDK from http://developer.android.com/sdk/index.html
#Unzip the android SDK in ../../
#So then the android tools will be in ../../android-sdk-linux_86/tools/
#
# now go to the available packages tab, check the box and click refresh,
# and download an SDK Platform
# Since I2P is configured to run on 1.1 or higher
# (API 2) download that one. Otherwise you must change the
# target in default.properties from android-2 to andriod-x
# where x is the API version.

# create a file local.properties with the following line:
# sdk-location=/path/to/your/android-sdk-linux_86

#then build the android apk file:
ant debug

# Create the android 1.1 (API 2) virtual device
# (don't make a custom hardware profile)
# A AVD created with the 1.5 SDK will not work with the newer tools
../../android-sdk-linux_86/tools/android create avd --name i2p --target 2

#then run the emulator:
../../android-sdk-linux_86/tools/emulator -avd i2p &

#then wait a couple minutes until the emulator is up
#then install the I2P app (ONE TIME ONLY)
ant install

#then run the debugger
../../android-sdk-linux_86/tools/ddms &

#to rebuild and reinstall to emulator:
ant reinstall
