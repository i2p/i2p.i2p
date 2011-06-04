These instructions are for a recent Android SDK (1.6 or later)..
Should also still work with a 1.5 SDK.
The build file is not compatible with the 1.1 SDK any more.
These instructions were last updated for SDK Tools Version 11 with
SDK Platform-tools Version 5, June 2011.

#Download the SDK from http://developer.android.com/sdk/index.html
#Unzip the android SDK in ../../
#So then the android tools will be in ../../android-sdk-linux_86/tools/
#
# Run the GUI updater, which you must do to get an SDK Platform:
../../android-sdk-linux_86/tools/android &

# now go to the available packages tab, check the box and click refresh,
# and download an SDK Platform
# Since I2P is configured to run on 1.1 or higher
# (API 2) download that one. Otherwise you must change the
# target in default.properties from android-2 to andriod-x
# where x is the API version.

# To run the debugger (ddms) you also need to download the
# "Android SDK Platform-Tools" package from the GUI updater.

# create a file local.properties with the following line (without the leading # of course):
# sdk.dir=/path/to/your/android-sdk-linux_86
# The old property was sdk-location=/path/to/your/android-sdk-linux_86
# but it changed in more recent tools.

# DO NOT create a new project or anything. It's all set up right here for you.

# Create the android 1.5 (API 3) virtual device
# (don't make a custom hardware profile)
# A AVD created with the 1.5 SDK will not work with the newer tools
../../android-sdk-linux_86/tools/android create avd --name i2p --target 3

#then run the emulator:
../../android-sdk-linux_86/tools/emulator -avd i2p &

#then wait a couple minutes until the emulator is up
#then install the I2P app
ant install

#then run the debugger
../../android-sdk-linux_86/tools/ddms &

#to rebuild and reinstall to emulator:
ant reinstall

# Now click on the I2P icon on your phone!

#other helpful commands
../../android-sdk-linux_86/tools/adb shell
