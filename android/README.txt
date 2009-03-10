#Unzip the android SDK in ../../
#So then the android tools will be in ../../android-sdk-linux_x86-1.1_r1/tools/

#then build the android apk file:
ant

#then run the emulator:
../../android-sdk-linux_x86-1.1_r1/tools/emulator &

#then wait a couple minutes until the emulator is up
#then install the I2P app
ant install

#then run the debugger
$A/ddms &

#to rebuild and reinstall to emulator:
ant reinstall
