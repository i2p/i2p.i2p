These instructions are for the 1.5 Android SDK.
The build file is not compatible with the 1.1 SDK any more.
1.6 and 2.0 SDKs are untested.

#Unzip the android SDK in ../../
#So then the android tools will be in ../../android-sdk-linux_x86-1.5_r2/tools/

# create a file local.properties with the following line:
# sdk-location=/path/to/your/android-sdk-linux_x86-1.5_r2

#then build the android apk file:
ant debug

# Create the android 1.5 virtual device
# (don't make a custom hardware profile)
../../android-sdk-linux_x86-1.5_r2/tools/android create avd --name i2p --target 2

#then run the emulator:
../../android-sdk-linux_x86-1.5_r2/tools/emulator -avd i2p &

#then wait a couple minutes until the emulator is up
#then install the I2P app
ant install

#then run the debugger
../../android-sdk-linux_x86-1.5_r2/tools/ddms &

#to rebuild and reinstall to emulator:
ant reinstall
