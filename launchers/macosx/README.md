# The Mac OS X Launcher

## The Event Manager

This is some Swift code which makes the application use events to signal the different compoents of the application. This seems to be the cleanest way since we're doing java subprocesses and so on. This can also be named a publish-subscribe system as well. If you're familiar with Javascript it would be a handy skill.

### Event Overview

| Event name | Details sent as arguments | Description |
| ------------- | ------------- | ------------- |
| router_start | nothing | This event get triggered when the router starts |
| router_exception | exception message | This will be triggered in case something within the functions that start the java (router) subprocess throws an exception |
| java_found | the location | This will be triggered once the DetectJava swift class has found java |
| router_must_upgrade | nothing | This will be triggered if no I2P is found, or if it's failing to get the version from the jar file |
| extract_completed | nothing | This is triggered when the deployment process is done extracting I2P to it's directory |
| router_can_start | nothing | This event will be triggered when I2P is found and a version number has been found, router won't start before this event |
| router_stop | error if any | Triggered when the router subprocess exits |
| router_pid | the pid number as string | Triggered when we know the pid of the router subprocess |
| router_version | the version string | Triggered when we have successfully extracted current I2P version |
| extract_errored | the error message | Triggered if the process didn't exit correctly |

## Misc

**Note** this project is WIP, cause Meeh has yet to merge in Obj-C/Swift code for GUI stuff in OSX.

However, this is a thin wrapper launching both Mac OS X trayicon and the I2P router - and make them talk together.

More code will be merged in, it's just a f* mess which Meeh needs to clean up and move into repo.

## Howto build

You can both build the project from the Xcode UI or you can build it from command line.

An example build command:
`xcodebuild -target I2PLauncher -sdk /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk`


