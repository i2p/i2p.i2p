#ifndef __APPDELEGATE_H__
#define __APPDELEGATE_H__


#include <string.h>
#include <memory.h>

#ifdef __cplusplus
#include <unistd.h>
#include <sys/types.h>
#include <pwd.h>
#include <assert.h>
#endif

#include <Cocoa/Cocoa.h>
#include "SBridge.h"


#include "RouterTask.h"

// TODO: Configure the project to avoid such includes.
#include "../version.h"

@class SwiftApplicationDelegate;
@class I2PDeployer;

@protocol SwiftMainDelegateProto
- (void)applicationDidFinishLaunching:(NSNotification *)aNotification;
@end


@class ExtractMetaInfo;

@interface ExtractMetaInfo : NSObject
@property (copy) NSString* i2pBase;
@property (copy) NSString* javaBinary;
@property (copy) NSString* zipFile;
@property (copy) NSString* jarFile;
@end


#ifdef __cplusplus

inline const char* RealHomeDirectory() {
  struct passwd *pw = getpwuid(getuid());
  assert(pw);
  return pw->pw_dir;
}

inline std::string getDefaultBaseDir()
{
  // Figure out base directory
  auto homeDir = RealHomeDirectory();
  const char* pathFromHome = "%s/Library/I2P";
  char buffer[strlen(homeDir)+strlen(pathFromHome)];
  sprintf(buffer, pathFromHome, homeDir);
  std::string i2pBaseDir(buffer);
  return i2pBaseDir;
}

inline std::string getDefaultLogDir()
{
  // Figure out log directory
  auto homeDir = RealHomeDirectory();
  const char* pathFromHome = "%s/Library/Logs/I2P";
  char buffer[strlen(homeDir)+strlen(pathFromHome)];
  sprintf(buffer, pathFromHome, homeDir);
  std::string i2pBaseDir(buffer);
  return i2pBaseDir;
}

inline void sendUserNotification(NSString* title, NSString* informativeText, bool makeSound = false) {
  NSUserNotification *userNotification = [[NSUserNotification alloc] init];
  
  userNotification.title = title;
  userNotification.informativeText = informativeText;
  NSBundle *launcherBundle = [NSBundle mainBundle];
  auto resPath = [launcherBundle resourcePath];
  auto stdResPath = std::string([resPath UTF8String]);
  stdResPath += "/AppImage.png";
  auto nsString = [[NSString alloc] initWithUTF8String:(const char*)stdResPath.c_str()];
  NSImage *appImage = [[NSImage alloc] initWithContentsOfFile:nsString];
  userNotification.contentImage = appImage;
  if (makeSound) userNotification.soundName = NSUserNotificationDefaultSoundName;
  
  [[NSUserNotificationCenter defaultUserNotificationCenter] scheduleNotification:userNotification];
};

#endif

@interface AppDelegate : NSObject <NSUserNotificationCenterDelegate, NSApplicationDelegate>
@property BOOL enableLogging;
@property BOOL enableVerboseLogging;
@property (assign) SwiftApplicationDelegate *swiftRuntime;
@property (assign) NSUserDefaults *userPreferences;
@property (assign) ExtractMetaInfo *metaInfo;
@property (assign) I2PDeployer *deployer;
@property (copy) NSImage *contentImage NS_AVAILABLE(10_9, NA);

- (void) extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion;
- (void) awakeFromNib;
- (void) applicationDidFinishLaunching:(NSNotification *)aNotification;
- (void) applicationWillTerminate:(NSNotification *)aNotification;
- (AppDelegate *) initWithArgc:(int)argc argv:(const char **)argv;
- (BOOL) userNotificationCenter:(NSUserNotificationCenter *)center
                               shouldPresentNotification:(NSUserNotification *)notification;
@end




#endif
