#include <functional>
#include <memory>
#include <iostream>
#include <algorithm>
#include <string>
#include <list>
#include <sys/stat.h>
#include <stdlib.h>
#include <future>
#include <vector>

#import <Foundation/Foundation.h>
#import <Foundation/NSFileManager.h>
#include <CoreFoundation/CFPreferences.h>

#import <objc/Object.h>
#import <Cocoa/Cocoa.h>
#import <AppKit/AppKit.h>
#import <AppKit/NSApplication.h>

#import "I2PLauncher-Swift.h"

#include "AppDelegate.h"
#include "RouterTask.h"
#include "include/fn.h"
#include "include/portcheck.h"
#import "SBridge.h"

#ifdef __cplusplus
#include <string>

#include "include/subprocess.hpp"
#include "include/strutil.hpp"

using namespace subprocess;

#endif

#define debug(format, ...) CFShow([NSString stringWithFormat:format, ## __VA_ARGS__]);

@interface AppDelegate () <NSUserNotificationCenterDelegate, NSApplicationDelegate>
@end


@implementation ExtractMetaInfo : NSObject
@end


@implementation AppDelegate

- (void) awakeFromNib {
}


- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
     shouldPresentNotification:(NSUserNotification *)notification {
  return YES;
}

#ifdef __cplusplus

- (void)extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion
{
  
  NSBundle *launcherBundle = [NSBundle mainBundle];
  auto homeDir = RealHomeDirectory();
  NSLog(@"Home directory is %s", homeDir);
  
  std::string basePath(homeDir);
  basePath.append("/Library/I2P");
  auto jarResPath = [launcherBundle pathForResource:@"launcher" ofType:@"jar"];
  NSLog(@"Trying to load launcher.jar from url = %@", jarResPath);
  self.metaInfo.jarFile = jarResPath;
  self.metaInfo.zipFile = [launcherBundle pathForResource:@"base" ofType:@"zip"];
  
  NSParameterAssert(basePath.c_str());
  NSError *error = NULL;
  BOOL success = NO;
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{


    try {
      std::string basearg("-Di2p.dir.base=");
      basearg += basePath;

      std::string zippath("-Di2p.base.zip=");
      zippath += [self.metaInfo.zipFile UTF8String];

      std::string jarfile("-cp ");
      jarfile += [self.metaInfo.jarFile UTF8String];

      // Create directory
      mkdir(basePath.c_str(), S_IRUSR | S_IWUSR | S_IXUSR);

      auto cli = defaultFlagsForExtractorJob;
      setenv("I2PBASE", basePath.c_str(), true);
      setenv("ZIPPATH", zippath.c_str(), true);
      //setenv("DYLD_LIBRARY_PATH",".:/usr/lib:/lib:/usr/local/lib", true);

      cli.push_back(basearg);
      cli.push_back(zippath);
      cli.push_back(jarfile);
      cli.push_back("net.i2p.launchers.BaseExtractor");
      auto rs = [[RouterProcessStatus alloc] init];
      
      std::string execStr = std::string([rs.getJavaHome UTF8String]);
      for_each(cli, [&execStr](std::string str){ execStr += std::string(" ") + str; });

      NSLog(@"Trying cmd: %@", [NSString stringWithUTF8String:execStr.c_str()]);
      try {
        sendUserNotification(APP_IDSTR, @"Please hold on while we extract I2P. You'll get a new message once done!");
        int extractStatus = Popen(execStr.c_str(), environment{{
          {"ZIPPATH", zippath.c_str()},
          {"I2PBASE", basePath.c_str()}
        }}).wait();
        NSLog(@"Extraction exit code %@",[NSString stringWithUTF8String:(std::to_string(extractStatus)).c_str()]);
        if (extractStatus == 0)
        {
          NSLog(@"Extraction complete!");
        }
      
      } catch (subprocess::OSError &err) {
          auto errMsg = [NSString stringWithUTF8String:err.what()];
          //success = NO;
          NSLog(@"Exception: %@", errMsg);
          sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg]);
      }

      // All done. Assume success and error are already set.
      dispatch_async(dispatch_get_main_queue(), ^{
        if (completion) {
          completion(success, error);
        }
      });
      
      
    } catch (OSError &err) {
      auto errMsg = [NSString stringWithUTF8String:err.what()];
      NSLog(@"Exception: %@", errMsg);
    }
  });
}

- (void)setApplicationDefaultPreferences {
  [self.userPreferences registerDefaults:@{
    @"enableLogging": @YES,
    @"enableVerboseLogging": @YES,
    @"autoStartRouter": @YES,
    @"i2pBaseDirectory": (NSString *)CFStringCreateWithCString(NULL, const_cast<const char *>(getDefaultBaseDir().c_str()), kCFStringEncodingUTF8)
  }];

  auto dict = [self.userPreferences dictionaryRepresentation];
  [self.userPreferences setPersistentDomain:dict forName:NSAPPDOMAIN];

  CFPreferencesSetMultiple((CFDictionaryRef)dict, NULL, CFAPPDOMAIN, kCFPreferencesCurrentUser, kCFPreferencesCurrentHost);
  CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);

  if (self.enableVerboseLogging) NSLog(@"Default preferences stored!");
}

#endif


- (void)applicationDidFinishLaunching:(NSNotification *)aNotification {
  // Init application here
  
  self.swiftRuntime = [[SwiftMainDelegate alloc] init];
  
  // This setup allows the application to send notifications
  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:self];
  
  
  // Start with user preferences
  self.userPreferences = [NSUserDefaults standardUserDefaults];
  [self setApplicationDefaultPreferences];
  self.enableLogging = [self.userPreferences boolForKey:@"enableLogging"];
  self.enableVerboseLogging = [self.userPreferences boolForKey:@"enableVerboseLogging"];
  // In case we are unbundled, make us a proper UI application
  [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
  [NSApp activateIgnoringOtherApps:YES];


#ifdef __cplusplus

  RouterProcessStatus* routerStatus = [[RouterProcessStatus alloc] init];
  std::string i2pBaseDir(getDefaultBaseDir());
  NSLog(@"i2pBaseDir = %s", i2pBaseDir.c_str());
  bool shouldAutoStartRouter = false;
  
  // TODO: Make the port a setting which defaults to 7657
  if (port_check(7657) != 0)
  {
    NSLog(@"Seems i2p is already running - I will not start the router (port 7657 is in use..)");
    sendUserNotification(@"Found already running router", @"TCP port 7657 seem to be used by another i2p instance.");
    
    [routerStatus setRouterStatus: true];
    [routerStatus setRouterRanByUs: false];
    shouldAutoStartRouter = false;
  } else {
    shouldAutoStartRouter = true;
  }

  NSBundle *launcherBundle = [NSBundle mainBundle];
  
  // Helper object to hold statefull path information
  self.metaInfo = [[ExtractMetaInfo alloc] init];
  self.metaInfo.i2pBase = [NSString stringWithUTF8String:i2pBaseDir.c_str()];
  self.metaInfo.javaBinary = [routerStatus getJavaHome];
  self.metaInfo.jarFile = [launcherBundle pathForResource:@"launcher" ofType:@"jar"];
  self.metaInfo.zipFile = [launcherBundle pathForResource:@"base" ofType:@"zip"];

  std::string basearg("-Di2p.dir.base=");
  basearg += i2pBaseDir;

  std::string jarfile("-cp ");
  jarfile += [self.metaInfo.zipFile UTF8String];
  
  // Might be hard to read if you're not used to Objective-C
  // But this is a "function call" that contains a "callback function"
  [routerStatus listenForEventWithEventName:@"router_can_start" callbackActionFn:^(NSString* information) {
    NSLog(@"Got signal, router can be started");
    [[SBridge sharedInstance] startupI2PRouter:self.metaInfo.i2pBase javaBinPath:self.metaInfo.javaBinary];
  }];
  
  // This will trigger the router start after an upgrade.
  [routerStatus listenForEventWithEventName:@"router_must_upgrade" callbackActionFn:^(NSString* information) {
    NSLog(@"Got signal, router must be upgraded");
    [self extractI2PBaseDir:^(BOOL success, NSError *error) {
      sendUserNotification(@"I2P is done extracting", @"I2P is now installed and ready to run!");
      NSLog(@"Done extracting I2P");
      [routerStatus triggerEventWithEn:@"router_can_start" details:@"upgrade complete"];
    }];
  }];
  
  // Initialize the Swift environment (the UI components)
  [self.swiftRuntime applicationDidFinishLaunching];

  struct stat sb;
  if ( !(stat(i2pBaseDir.c_str(), &sb) == 0 && S_ISDIR(sb.st_mode)) )
  {
    // I2P is not extracted.
    if (self.enableVerboseLogging) NSLog(@"I2P Directory don't exists!");
    [routerStatus triggerEventWithEn:@"router_must_upgrade" details:@"deploy needed"];
  } else {
    // I2P was already found extracted
    NSLog(@"Time to detect I2P version in install directory");
    [self.swiftRuntime findInstalledI2PVersion];
  }
  
#endif
}

/**
 *
 * Exit sequence
 *
 **/
- (void)applicationWillTerminate:(NSNotification *)aNotification {
  // Tear down here
  NSString *string = @"applicationWillTerminate executed";
  NSLog(@"%@", string);
  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:nil];
}


/* wrapper for main */
- (AppDelegate *)initWithArgc:(int)argc argv:(const char **)argv {
  return self;
}
@end



int main(int argc, const char **argv)
{
  NSApplication *app = [NSApplication sharedApplication];

  AppDelegate *appDelegate = [[AppDelegate alloc] initWithArgc:argc argv:argv];
  app.delegate = appDelegate;
  auto mainBundle = [NSBundle mainBundle];
  NSString* stringNameBundle = [mainBundle objectForInfoDictionaryKey:(NSString *)kCFBundleNameKey];
  if ([[NSRunningApplication runningApplicationsWithBundleIdentifier:[mainBundle bundleIdentifier]] count] > 1) {
    [[NSAlert alertWithMessageText:[NSString stringWithFormat:@"Another copy of %@ is already running.",stringNameBundle]
                     defaultButton:nil alternateButton:nil otherButton:nil informativeTextWithFormat:@"This copy will now quit."] runModal];
    
    [NSApp terminate:nil];
  }
  [NSBundle loadNibNamed:@"I2Launcher" owner:NSApp];

  [NSApp run];
  return 0;
}



