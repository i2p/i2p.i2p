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


#include <CoreFoundation/CoreFoundation.h>
#include <CoreFoundation/CFStream.h>
#include <CoreFoundation/CFPropertyList.h>
#include <CoreFoundation/CFDictionary.h>
#include <CoreFoundation/CFArray.h>
#include <CoreFoundation/CFString.h>
#include <CoreFoundation/CFPreferences.h>

#import <objc/Object.h>
#import <Cocoa/Cocoa.h>
#import <AppKit/AppKit.h>
#import <AppKit/NSApplication.h>

#import "I2PLauncher-Swift.h"

#include "AppDelegate.h"
#include "RouterTask.h"
#include "JavaHelper.h"
#include "include/fn.h"
#include "include/portcheck.h"

#define debug(format, ...) CFShow([NSString stringWithFormat:format, ## __VA_ARGS__]);

@interface AppDelegate () <NSUserNotificationCenterDelegate, NSApplicationDelegate>
@end

#ifdef __cplusplus
#import "SBridge.h"
JvmListSharedPtr gRawJvmList = nullptr;
#endif


@interface AppDelegate () <NSUserNotificationCenterDelegate, NSApplicationDelegate>
@end

#ifdef __cplusplus
maybeAnRouterRunner getGlobalRouterObject()
{
    std::lock_guard<std::mutex> lock(globalRouterStatusMutex);
    return globalRouterStatus; // Remember this might be nullptr now.
}

void setGlobalRouterObject(I2PRouterTask* newRouter)
{
    std::lock_guard<std::mutex> lock(globalRouterStatusMutex);
    globalRouterStatus = newRouter;
}


pthread_mutex_t mutex;

bool getGlobalRouterIsRunning()
{
    pthread_mutex_lock(&mutex);
    bool current = isRuterRunning;
    pthread_mutex_unlock(&mutex);
    return current;
}
void setGlobalRouterIsRunning(bool running)
{
    pthread_mutex_lock(&mutex);
    isRuterRunning = running;
    pthread_mutex_unlock(&mutex);
}

#endif


@implementation ExtractMetaInfo : NSObject
@end


@implementation AppDelegate

- (void) awakeFromNib {
}

#ifdef __cplusplus

#include <unistd.h>
#include <sys/types.h>
#include <pwd.h>
#include <assert.h>

#include "include/subprocess.hpp"
#include "include/strutil.hpp"

using namespace subprocess;

const char* RealHomeDirectory() {
  struct passwd *pw = getpwuid(getuid());
  assert(pw);
  return pw->pw_dir;
}

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

      auto cli = JavaRunner::defaultFlagsForExtractorJob;
      setenv("I2PBASE", basePath.c_str(), true);
      setenv("ZIPPATH", zippath.c_str(), true);
      //setenv("DYLD_LIBRARY_PATH",".:/usr/lib:/lib:/usr/local/lib", true);

      cli.push_back(basearg);
      cli.push_back(zippath);
      cli.push_back(jarfile);
      cli.push_back("net.i2p.launchers.BaseExtractor");
      auto rs = [[RouterProcessStatus alloc] init];
      NSString* jh = [rs getJavaHome];
      if (jh != nil) {
        NSLog(@"jh er %@", jh);
      }
      
      NSString* newString = [NSString stringWithFormat:@"file://%@", rs.getJavaHome];
      NSURL *baseURL = [NSURL fileURLWithPath:newString];
      
      NSLog(@"MEEH URL PATH: %s", [baseURL fileSystemRepresentation]);

      auto charCli = map(cli, [](std::string str){ return str.c_str(); });
      std::string execStr = std::string([rs.getJavaHome UTF8String]);
      // TODO: Cheap hack, make it better.
      replace(execStr, "Internet Plug-Ins", "Internet\\ Plug-Ins");
      replace(execStr, "\n", "");
      NSLog(@"Java path1 = %s", execStr.c_str());
      [rs setJavaHome: [NSString stringWithFormat:@"%s", execStr.c_str()]];
      for_each(cli, [&execStr](std::string str){ execStr += std::string(" ") + str; });
      
      //execStr = replace(execStr, "\\\\ ", "\\ ");
      //NSLog(@"Java path2 = %s", execStr.c_str());

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
          //success = YES;
          NSLog(@"Time to detect I2P version in install directory");
          [self.swiftRuntime findInstalledI2PVersion];
        }
      
      } catch (subprocess::OSError &err) {
          auto errMsg = [NSString stringWithUTF8String:err.what()];
          //success = NO;
          NSLog(@"Exception: %@", errMsg);
          sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg]);
      }

      // All done. Assume success and error are already set.
      dispatch_async(dispatch_get_main_queue(), ^{
        //sendUserNotification(APP_IDSTR, @"Extraction complete!", self.contentImage);
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

#endif

- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
                               shouldPresentNotification:(NSUserNotification *)notification {
    return YES;
}


#ifdef __cplusplus

inline std::string getDefaultBaseDir()
{
  // Figure out base directory
  const char* pathFromHome = "/Users/%s/Library/I2P";
  auto username = getenv("USER");
  char buffer[strlen(pathFromHome)+strlen(username)];
  sprintf(buffer, pathFromHome, username);
  std::string i2pBaseDir(buffer);
  return i2pBaseDir;
}

- (NSString *)userSelectJavaHome:(JvmListPtr)rawJvmList
{
  NSString *appleScriptString = @"set jvmlist to {\"Newest\"";
  for (auto item : *rawJvmList) {
    auto str = strprintf(",\"%s\"", item->JVMName.c_str()).c_str();
    NSString* tmp = [NSString stringWithUTF8String:str];
    appleScriptString = [appleScriptString stringByAppendingString:tmp];
  }
  appleScriptString = [appleScriptString stringByAppendingString:@"}\nchoose from list jvmlist\n"];
  NSAppleScript *theScript = [[NSAppleScript alloc] initWithSource:appleScriptString];
  NSDictionary *theError = nil;
  NSString* userResult = [[theScript executeAndReturnError: &theError] stringValue];
  NSLog(@"User choosed %@.\n", userResult);
  if (theError != nil)
  {
    NSLog(@"Error: %@.\n", theError);
  }
  return userResult;
}


- (void)userChooseJavaHome {
  listAllJavaInstallsAvailable();
  std::shared_ptr<JvmHomeContext> appContext = std::shared_ptr<JvmHomeContext>( new JvmHomeContext() );
  for (auto item : *appContext->getJvmList()) {
    printf("JVM %s (Version: %s, Directory: %s)\n", item->JVMName.c_str(), item->JVMPlatformVersion.c_str(), item->JVMHomePath.c_str());
  }
  JvmListPtr rawJvmList = appContext->getJvmList();
  NSString * userJavaHome = [self userSelectJavaHome: rawJvmList];
  // TODO: Add logic so user can set preferred JVM
}

#endif

- (void)setApplicationDefaultPreferences {
  auto defaultJVMHome = check_output({"/usr/libexec/java_home","-v",DEF_MIN_JVM_VER});
  auto tmpStdStr = std::string(defaultJVMHome.buf.data());
  trim(tmpStdStr);
  auto cfDefaultHome  = CFStringCreateWithCString(NULL, const_cast<const char *>(tmpStdStr.c_str()), kCFStringEncodingUTF8);
  /*[self.userPreferences registerDefaults:@{
    @"javaHome" : (NSString *)cfDefaultHome,
    @"lastI2PVersion" : (NSString *)CFSTR(DEF_I2P_VERSION),
    @"enableLogging": @YES,
    @"enableVerboseLogging": @YES,
    @"autoStartRouter": @YES,
    @"i2pBaseDirectory": (NSString *)CFStringCreateWithCString(NULL, const_cast<const char *>(getDefaultBaseDir().c_str()), kCFStringEncodingUTF8)
  }];*/
  if (self.enableVerboseLogging) NSLog(@"Default JVM home preference set to: %@", cfDefaultHome);

  auto dict = [self.userPreferences dictionaryRepresentation];
  [self.userPreferences setPersistentDomain:dict forName:NSAPPDOMAIN];

  CFPreferencesSetMultiple((CFDictionaryRef)dict, NULL, CFAPPDOMAIN, kCFPreferencesCurrentUser, kCFPreferencesCurrentHost);
  CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);

  if (self.enableVerboseLogging) NSLog(@"Default preferences stored!");
}


- (void)applicationDidFinishLaunching:(NSNotification *)aNotification {
  // Init application here
  
  self.swiftRuntime = [[SwiftMainDelegate alloc] init];
  
  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:self];
  // Start with user preferences
  self.userPreferences = [NSUserDefaults standardUserDefaults];
  [self setApplicationDefaultPreferences];
  self.enableLogging = [self.userPreferences boolForKey:@"enableLogging"];
  self.enableVerboseLogging = [self.userPreferences boolForKey:@"enableVerboseLogging"];


#ifdef __cplusplus
  gRawJvmList = std::make_shared<std::list<JvmVersionPtr> >(std::list<JvmVersionPtr>());
#endif
  // In case we are unbundled, make us a proper UI application
  [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
  [NSApp activateIgnoringOtherApps:YES];

  // TODO: Also check for new installations from time to time.
  
#ifdef __cplusplus
  auto javaHomePref = [self.userPreferences stringForKey:@"javaHome"];
  if (self.enableVerboseLogging)
  {
    NSLog(@"Java home from preferences: %@", javaHomePref);
  }

  if (self.enableVerboseLogging)
  {
    NSString *appDomain = [[NSBundle mainBundle] bundleIdentifier];
    NSLog(@"Appdomain is: %@", appDomain);
  }

  NSLog(@"We should have started the statusbar object by now...");
  RouterProcessStatus* routerStatus = [[RouterProcessStatus alloc] init];

  std::string i2pBaseDir(getDefaultBaseDir());

  auto pref = self.userPreferences;
  
  bool shouldAutoStartRouter = false;

  if (port_check(7657) != 0)
  {
    NSLog(@"Seems i2p is already running - I will not start the router (port 7657 is in use..)");
    sendUserNotification(@"Found already running router", @"TCP port 7657 seem to be used by another i2p instance.");
    
    [routerStatus setRouterStatus: true];
    [routerStatus setRouterRanByUs: false];
    return;
  } else {
    shouldAutoStartRouter = true;
  }

  if (self.enableVerboseLogging) NSLog(@"processinfo %@", [[NSProcessInfo processInfo] arguments]);

  auto getJavaBin = [&pref,&self]() -> std::string {
    // Get Java home
    /*NSString* val = @"";
    val = [pref stringForKey:@"javaHome"];
    if (val == NULL) val = @"";
    if (self.enableVerboseLogging) NSLog(@"Javahome: %@", val);
    auto javaHome = std::string([val UTF8String]);
    //trim(javaHome); // Trim to remove endline
    auto javaBin = std::string(javaHome);
    javaBin += "/bin/java"; // Append java binary to path.
    return javaBin;*/
    DetectJava *dt = [[DetectJava alloc] init];
    [dt findIt];
    if ([dt isJavaFound]) {
      return [dt.javaHome UTF8String];
    } else {
      throw new std::runtime_error("Java home fatal error");
    }
  };


  NSBundle *launcherBundle = [NSBundle mainBundle];
  
  auto jarResPath = [launcherBundle pathForResource:@"launcher" ofType:@"jar"];
  NSLog(@"Trying to load launcher.jar from url = %@", jarResPath);
    
  self.metaInfo = [[ExtractMetaInfo alloc] init];
  //self.metaInfo.i2pBase = [NSString stringWithUTF8String:i2pBaseDir.c_str()];
  self.metaInfo.javaBinary = [NSString stringWithUTF8String:getJavaBin().c_str()];
  self.metaInfo.jarFile = [launcherBundle pathForResource:@"launcher" ofType:@"jar"];
  self.metaInfo.zipFile = [launcherBundle pathForResource:@"base" ofType:@"zip"];

  std::string basearg("-Di2p.dir.base=");
  //basearg += i2pBaseDir;

  std::string jarfile("-cp ");
  jarfile += [self.metaInfo.zipFile UTF8String];
  

  struct stat sb;
  if ( !(stat(i2pBaseDir.c_str(), &sb) == 0 && S_ISDIR(sb.st_mode)) )
  {
    // I2P is not extracted.
    if (self.enableVerboseLogging) NSLog(@"I2P Directory don't exists!");

    [self extractI2PBaseDir:^(BOOL success, NSError *error) {
      sendUserNotification(@"I2P is done extracting", @"I2P is now installed and ready to run!");
      [self.swiftRuntime applicationDidFinishLaunching];
      NSLog(@"Done extracting I2P");
      if (shouldAutoStartRouter) [self startupI2PRouter];
    }];

  } else {
    if (self.enableVerboseLogging) NSLog(@"I2P directory found!");
    if (shouldAutoStartRouter) [self startupI2PRouter];
    [self.swiftRuntime applicationDidFinishLaunching];
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
  //NSAutoreleasePool * pool = [[NSAutoreleasePool alloc] init];

  AppDelegate *appDelegate = [[AppDelegate alloc] initWithArgc:argc argv:argv];
  app.delegate = appDelegate;
  [NSBundle loadNibNamed:@"I2Launcher" owner:NSApp];

  [NSApp run];
  // Handle any errors
  //[pool drain];
  return 0;
}



