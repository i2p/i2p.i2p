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
| router_already_running | an error message | Triggered if any processes containing i2p.jar in name/arguments already exists upon router launch |

## Misc

**Note** this project is WIP, cause Meeh has yet to merge in Obj-C/Swift code for GUI stuff in OSX.

However, this is a thin wrapper launching both Mac OS X trayicon and the I2P router - and make them talk together.

More code will be merged in, it's just a f* mess which Meeh needs to clean up and move into repo.

### Java Auto Downloader

We got a quite reliable source regarding the download url,
https://github.com/Homebrew/homebrew-cask/raw/master/Casks/java.rb

Homebrew is quite well maintained on OSX and any updates at Oracle should quickly reflect to brew, which again will be noticed upon builds.

## Howto build

You can both build the project from the Xcode UI or you can build it from command line.

An example build command:
`REPACK_I2P=1 xcodebuild -target I2PLauncher -sdk /Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX.sdk`

**Note** you don't need REPACK_I2P except for the first build, and each time you wish to repack the zip file containing i2p.

## Objective-C / Swift Links

* https://nshipster.com/at-compiler-directives/
* https://developer.apple.com/documentation/swift/cocoa_design_patterns/handling_cocoa_errors_in_swift
* https://content.pivotal.io/blog/rails-to-ios-what-the-are-these-symbols-in-my-code
* https://mackuba.eu/2008/10/05/learn-objective-c-in-30-minutes/
* https://en.wikipedia.org/wiki/Objective-C
* http://cocoadevcentral.com/d/learn_objectivec/

