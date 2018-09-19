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


#include "RouterTask.h"
#include "JavaHelper.h"


#define DEF_I2P_VERSION "0.9.36"
#define APPDOMAIN "net.i2p.launcher"
#define NSAPPDOMAIN @APPDOMAIN
#define CFAPPDOMAIN CFSTR(APPDOMAIN)
#define APP_IDSTR @"I2P Launcher"

@class SwiftMainDelegate;

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

inline void sendUserNotification(NSString* title, NSString* informativeText, NSImage* contentImage = NULL, bool makeSound = false) {
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

using maybeAnRouterRunner = I2PRouterTask*;

std::vector<std::string> buildClassPath(std::string basePath);

extern JvmListSharedPtr gRawJvmList;

// DO NOT ACCESS THIS GLOBAL VARIABLE DIRECTLY.
static std::mutex globalRouterStatusMutex;
static maybeAnRouterRunner globalRouterStatus = maybeAnRouterRunner{};
static bool isRuterRunning = false;

maybeAnRouterRunner getGlobalRouterObject();
void setGlobalRouterObject(I2PRouterTask* newRouter);
bool getGlobalRouterIsRunning();
void setGlobalRouterIsRunning(bool running);

#include "SBridge.h"

#endif

@class MenuBarCtrl;

@interface AppDelegate : NSObject <NSUserNotificationCenterDelegate, NSApplicationDelegate> {
@public
  //NSImageView *imageCell;
}
@property BOOL enableLogging;
@property BOOL enableVerboseLogging;
@property (assign) SwiftMainDelegate *swiftRuntime;
@property (assign) NSUserDefaults *userPreferences;
@property (assign) ExtractMetaInfo *metaInfo;
@property (copy) NSImage *contentImage NS_AVAILABLE(10_9, NA);

- (void) extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion;
- (void) awakeFromNib;
- (void) applicationDidFinishLaunching:(NSNotification *)aNotification;
- (void) applicationWillTerminate:(NSNotification *)aNotification;
- (void) setApplicationDefaultPreferences;
- (void) userChooseJavaHome;
- (AppDelegate *) initWithArgc:(int)argc argv:(const char **)argv;
#ifdef __cplusplus
- (void) startupI2PRouter;
- (NSString *) userSelectJavaHome:(JvmListPtr)rawJvmList;
#endif
- (BOOL) userNotificationCenter:(NSUserNotificationCenter *)center
                               shouldPresentNotification:(NSUserNotification *)notification;
@end




#endif
