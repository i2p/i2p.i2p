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
#import "Deployer.h"
#include "logger_c.h"

#ifdef __cplusplus
#include <string>

#include "include/subprocess.hpp"
#include "include/strutil.hpp"

#include "Logger.h"
#include "LoggerWorker.hpp"

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

- (void)extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion
{
  self.deployer = [[I2PDeployer alloc] initWithMetaInfo:self.metaInfo];
  [self.deployer extractI2PBaseDir:completion];
}

- (void)applicationDidFinishLaunching:(NSNotification *)aNotification {
  // Init application here
  
  // Here we initialize the swift code which would do most of the job from now on.
  self.swiftRuntime = [[SwiftApplicationDelegate alloc] init];
  
  // This setup allows the application to send notifications
  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:self];
  
  
  // Start with user preferences
  self.userPreferences = [NSUserDefaults standardUserDefaults];
  // In case we are unbundled, make us a proper UI application
  [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
  [NSApp activateIgnoringOtherApps:YES];


#ifdef __cplusplus

  RouterProcessStatus* routerStatus = [[RouterProcessStatus alloc] init];
  std::string i2pBaseDir(getDefaultBaseDir());
  MLOG(INFO) << "i2pBaseDir = " << i2pBaseDir.c_str();
  bool shouldAutoStartRouter = false;
  
  // Initialize the Swift environment (the UI components)
  [self.swiftRuntime applicationDidFinishLaunching];
  
  NSInteger portNum = [self.userPreferences integerForKey:@"consolePortCheckNum"];
  if (port_check((int)portNum) != 0)
  {
    NSLog(@"Seems i2p is already running - I will not start the router (port %d is in use..)", (int)portNum);
    sendUserNotification(@"Found already running router", @"TCP port 7657 seem to be used by another i2p instance.");
    
    [routerStatus setRouterStatus: true];
    [routerStatus setRouterRanByUs: false];
    shouldAutoStartRouter = false;
  } else {
    shouldAutoStartRouter = true;
  }
  if (![self.userPreferences boolForKey:@"startRouterAtLogin"] && ![self.userPreferences boolForKey:@"startRouterAtStartup"])
  {
    // In this case we don't want to find a running service
    std::string launchdFile(RealHomeDirectory());
    launchdFile += "/Library/LaunchAgents/net.i2p.macosx.I2PRouter.plist";
    
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
  
  // This will trigger the router start after an upgrade.
  [routerStatus listenForEventWithEventName:@"router_must_upgrade" callbackActionFn:^(NSString* information) {
    NSLog(@"Got signal, router must be deployed from base.zip");
    [self extractI2PBaseDir:^(BOOL success, NSError *error) {
      if (success) {
        sendUserNotification(@"I2P is done extracting", @"I2P is now installed and ready to run!");
        NSLog(@"Done extracting I2P");
        [routerStatus triggerEventWithEn:@"extract_completed" details:@"upgrade complete"];
      } else {
        NSLog(@"Error while extracting I2P");
        [routerStatus triggerEventWithEn:@"extract_errored" details:[NSString stringWithFormat:@"%@", error]];
      }
    }];
  }];
  
  NSString *nsI2PBaseStr = [NSString stringWithUTF8String:i2pBaseDir.c_str()];

  [routerStatus listenForEventWithEventName:@"extract_completed" callbackActionFn:^(NSString* information) {
    NSLog(@"Time to detect I2P version in install directory");
    [self.swiftRuntime findInstalledI2PVersion];
  }];
  
  //struct stat sb;
  //if ( !(stat(i2pBaseDir.c_str(), &sb) == 0 && S_ISDIR(sb.st_mode)) )
  BOOL shouldBeTrueOnReturnDir = YES;
  if (! [NSFileManager.defaultManager fileExistsAtPath: nsI2PBaseStr isDirectory: &shouldBeTrueOnReturnDir])
  {
    // I2P is not extracted.
    if (shouldBeTrueOnReturnDir) {
      if (self.enableVerboseLogging) NSLog(@"I2P Directory don't exists!");
      [routerStatus triggerEventWithEn:@"router_must_upgrade" details:@"deploy needed"];
    } else {
      // TODO: handle if i2p path exists but it's not a dir.
    }
  } else {
    // I2P was already found extracted
    NSString *nsI2pJar = [NSString stringWithFormat:@"%@/lib/i2p.jar", nsI2PBaseStr];
    
    // But does I2PBASE/lib/i2p.jar exists?
    if ([NSFileManager.defaultManager fileExistsAtPath:nsI2pJar]) {
      NSLog(@"Time to detect I2P version in install directory");
      [self.swiftRuntime findInstalledI2PVersion];
    } else {
      // The directory exists, but not i2p.jar - most likely we're in mid-extraction state.
      [routerStatus triggerEventWithEn:@"router_must_upgrade" details:@"deploy needed"];
    }
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
  [self.swiftRuntime applicationWillTerminate];
  NSString *string = @"applicationWillTerminate executed";
  NSLog(@"%@", string);
  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:nil];
}


/* wrapper for main */
- (AppDelegate *)initWithArgc:(int)argc argv:(const char **)argv {
  return self;
}
@end

#ifdef __cplusplus
namespace {
  const std::string logDirectory = getDefaultLogDir();
}
#endif

int main(int argc, const char **argv)
{
  NSApplication *app = [NSApplication sharedApplication];

#ifdef __cplusplus
  mkdir(logDirectory.c_str(), S_IRUSR | S_IWUSR | S_IXUSR);
  
  SharedLogWorker logger("I2PLauncher", logDirectory);
  MeehLog::initializeLogging(&logger);
  
  MLOG(INFO) << "Application is starting up";
#endif
  
  AppDelegate *appDelegate = [[AppDelegate alloc] initWithArgc:argc argv:argv];
  app.delegate = appDelegate;
  auto mainBundle = [NSBundle mainBundle];
  NSString* stringNameBundle = [mainBundle objectForInfoDictionaryKey:(NSString *)kCFBundleNameKey];
  if ([[NSRunningApplication runningApplicationsWithBundleIdentifier:[mainBundle bundleIdentifier]] count] > 1) {
    [[NSAlert alertWithMessageText:[NSString stringWithFormat:@"Another copy of %@ is already running.",stringNameBundle]
                     defaultButton:nil alternateButton:nil otherButton:nil informativeTextWithFormat:@"This copy will now quit."] runModal];
    
    [NSApp terminate:nil];
  }
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
  [NSBundle loadNibNamed:@"I2Launcher" owner:NSApp];
#pragma GCC diagnostic pop
  
  [NSApp run];
  return 0;
}



