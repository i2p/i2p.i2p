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

#include "AppDelegate.h"
#include "StatusItemButton.h"
#include "RouterTask.h"
#include "JavaHelper.h"
#include "fn.h"
#include "optional.hpp"

#define debug(format, ...) CFShow([NSString stringWithFormat:format, ## __VA_ARGS__]);

JvmListSharedPtr gRawJvmList = nullptr;


@interface MenuBarCtrl () <StatusItemButtonDelegate, NSMenuDelegate>
@end

@interface AppDelegate () <NSUserNotificationCenterDelegate, NSApplicationDelegate>
@end


maybeAnRouterRunner getGlobalRouterObject()
{
    std::lock_guard<std::mutex> lock(globalRouterStatusMutex);
    return globalRouterStatus;
}

void setGlobalRouterObject(RouterTask* newRouter)
{
    std::lock_guard<std::mutex> lock(globalRouterStatusMutex);
    globalRouterStatus.emplace(newRouter);
}

std::future<int> startupRouter(NSString* javaBin, NSArray<NSString*>* arguments, NSString* i2pBaseDir) {
/*
  NSLog(@"Arguments: %@", [NSString stringWithUTF8String:arguments.c_str()]);
  auto launchLambda = [](JavaRunner *javaRun) {
    javaRun->javaProcess->start_process();
    auto pid = javaRun->javaProcess->pid();
    std::cout << "I2P Router process id = " << pid << std::endl;

    // Blocking
    javaRun->javaProcess->wait();
  };
  auto callbackAfterExit = [](){
    printf("Callback after exit\n");
  };
  NSLog(@"Still fine!");

  setGlobalRouterObject(new JavaRunner{ javaBin, arguments, i2pBaseDir, std::move(launchLambda), std::move(callbackAfterExit) });

  NSLog(@"Still fine!");
  return std::async(std::launch::async, [&]{
      getGlobalRouterObject().value()->execute();
      return 0;
    });
*/
    //CFShow(arguments);

    @try {
        RTaskOptions* options = [RTaskOptions alloc];
        options.binPath = javaBin;
        options.arguments = arguments;
        options.i2pBaseDir = i2pBaseDir;
        auto instance = [[[RouterTask alloc] initWithOptions: options] autorelease];
        setGlobalRouterObject(instance);
        //NSThread *thr = [[NSThread alloc] initWithTarget:instance selector:@selector(execute) object:nil];
        [instance execute];
        sendUserNotification(APP_IDSTR, @"The I2P router is starting up.");
        auto pid = [instance getPID];
        return std::async(std::launch::async, [&pid]{
          return pid;
        });
    }
    @catch (NSException *e)
	{
        auto errStr = [NSString stringWithFormat:@"Expection occurred %@",[e reason]];
		NSLog(@"%@", errStr);
        sendUserNotification(APP_IDSTR, errStr);
        return std::async(std::launch::async, [&]{
          return 0;
        });
	}
}

void openUrl(NSString* url)
{
    [[NSWorkspace sharedWorkspace] openURL:[NSURL URLWithString: url]];
}


@implementation MenuBarCtrl

- (void) statusItemButtonLeftClick: (StatusItemButton *) button
{
  CFShow(CFSTR("Left button clicked!"));
  NSEvent *event = [NSApp currentEvent];
}

- (void) statusItemButtonRightClick: (StatusItemButton *) button
{
  CFShow(CFSTR("Right button clicked!"));
  NSEvent *event = [NSApp currentEvent];
  [self.statusItem popUpStatusItemMenu: self.menu];
}

- (void)statusBarImageBtnClicked
{
  [NSTimer scheduledTimerWithTimeInterval:10 target:self selector:@selector(btnPressedAction) userInfo:nil repeats:NO];
}

- (void)btnPressedAction:(id)sender
{
  NSLog(@"Button presseeeeeeed");
  NSEvent *event = [NSApp currentEvent];
}

- (void) openRouterConsoleBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked openRouterConsoleBtnHandler");
  openUrl(@"http://127.0.0.1:7657");
}

- (void) startJavaRouterBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked startJavaRouterBtnHandler");
}

- (void) restartJavaRouterBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked restartJavaRouterBtnHandler");
  if (getGlobalRouterObject().has_value())
  {
      sendUserNotification(APP_IDSTR, @"Requesting the I2P router to restart.");
      [getGlobalRouterObject().value() requestRestart];
      NSLog(@"Requested restart");
  }
}

- (void) stopJavaRouterBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked stopJavaRouterBtnHandler");
  if (getGlobalRouterObject().has_value())
  {
      sendUserNotification(APP_IDSTR, @"Requesting the I2P router to shutdown.");
      [getGlobalRouterObject().value() requestShutdown];
      NSLog(@"Requested shutdown");
  }
}

- (void) quitWrapperBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"quitWrapper event handler called!");
  [[NSApplication sharedApplication] terminate:self];
}

- (MenuBarCtrl *) init
{
  self.menu = [self createStatusBarMenu];
  self.statusItem = [[NSStatusBar systemStatusBar] statusItemWithLength:NSVariableStatusItemLength];

  self.image = [NSImage imageNamed:@"ItoopieTransparent.png"];
  [self.image setTemplate:YES];
  self.statusItem.image = self.image;

  self.statusItem.highlightMode = NO;
  self.statusItem.toolTip = @"I2P Router Controller";

  self.statusBarButton = [[StatusItemButton alloc] initWithImage:self.image];
  self.statusBarButton.menu = self.menu;

  // Selecting action
  //[self.statusBarButton setAction:@selector(statusBarImageBtnClicked)];
  //[self.statusBarButton setTarget:self];
  self.statusBarButton.delegate = self;
  [self.statusItem popUpStatusItemMenu: self.menu];

  [self.statusItem setView: self.statusBarButton];
  NSLog(@"Initialized statusbar and such");
  return self;
}

-(void) dealloc
{
  [self.image release];
  [self.menu release];
}

- (NSMenu *)createStatusBarMenu
{
  NSMenu *menu = [[NSMenu alloc] init];
  [menu setAutoenablesItems:NO];

  NSMenuItem *openConsoleI2Pbtn =
    [[NSMenuItem alloc] initWithTitle:@"Open Console"
                        action:@selector(openRouterConsoleBtnHandler:)
                        keyEquivalent:@""];
  [openConsoleI2Pbtn setTarget:self];
  [openConsoleI2Pbtn setEnabled:YES];

  NSMenuItem *startI2Pbtn =
    [[NSMenuItem alloc] initWithTitle:@"Start I2P"
                        action:@selector(startJavaRouterBtnHandler:)
                        keyEquivalent:@""];
  [startI2Pbtn setTarget:self];
  if ([self.userPreferences boolForKey:@"autoStartRouter"])
  {
    [startI2Pbtn setEnabled:NO];
  } else {
    [startI2Pbtn setEnabled:YES];
  }

  NSMenuItem *restartI2Pbtn =
    [[NSMenuItem alloc] initWithTitle:@"Restart I2P"
                        action:@selector(restartJavaRouterBtnHandler:)
                        keyEquivalent:@""];
  [restartI2Pbtn setTarget:self];
  [restartI2Pbtn setEnabled:YES];

  NSMenuItem *stopI2Pbtn =
    [[NSMenuItem alloc] initWithTitle:@"Stop I2P"
                        action:@selector(stopJavaRouterBtnHandler:)
                        keyEquivalent:@""];
  [stopI2Pbtn setTarget:self];
  [stopI2Pbtn setEnabled:YES];

  NSMenuItem *quitWrapperBtn =
    [[NSMenuItem alloc] initWithTitle:@"Quit I2P Wrapper"
                        action:@selector(quitWrapperBtnHandler:)
                        keyEquivalent:@""];
  [quitWrapperBtn setTarget:self];
  [quitWrapperBtn setEnabled:YES];


  [menu addItem:openConsoleI2Pbtn];
  [menu addItem:startI2Pbtn];
  [menu addItem:stopI2Pbtn];
  [menu addItem:restartI2Pbtn];
  [menu addItem:quitWrapperBtn];
  return menu;
}

@end

@implementation ExtractMetaInfo
@end

@implementation AppDelegate

- (void)extractI2PBaseDir:(ExtractMetaInfo *)metaInfo completion:(void(^)(BOOL success, NSError *error))completion
{
  std::string basePath([metaInfo.i2pBase UTF8String]);
  NSParameterAssert(metaInfo.i2pBase);
  NSError *error = NULL;
  BOOL success;
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{

    // Get paths
    NSBundle *launcherBundle = [NSBundle mainBundle];

    std::string basearg("-Di2p.dir.base=");
    basearg += basePath;

    std::string zippath("-Di2p.base.zip=");
    zippath += [metaInfo.zipFile UTF8String];

    std::string jarfile("-cp ");
    jarfile += [metaInfo.jarFile UTF8String];

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

    //auto charCli = map(cli, [](std::string str){ return str.c_str(); });
    std::string execStr = [metaInfo.javaBinary UTF8String];
    for_each(cli, [&execStr](std::string str){ execStr += std::string(" ") + str; });

    NSLog(@"Trying cmd: %@", [NSString stringWithUTF8String:execStr.c_str()]);
    try {
        sendUserNotification(APP_IDSTR, @"Please hold on while we extract I2P. You'll get a new message once done!", self.contentImage);
        int extractStatus = Popen(execStr.c_str(), environment{{
            {"ZIPPATH", zippath.c_str()},
            {"I2PBASE", basePath.c_str()}
        }}).wait();
        NSLog(@"Extraction exit code %@",[NSString stringWithUTF8String:(std::to_string(extractStatus)).c_str()]);
        if (extractStatus == 0)
        {
            //success = YES;
        }
    } catch (subprocess::OSError &err) {
        auto errMsg = [NSString stringWithUTF8String:err.what()];
        //success = NO;
        NSLog(@"Exception: %@", errMsg);
        sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg], self.contentImage);
    }

    // All done. Assume success and error are already set.
    dispatch_async(dispatch_get_main_queue(), ^{
      sendUserNotification(APP_IDSTR, @"Extraction complete!", self.contentImage);
      if (completion) {
        completion(success, error);
      }
    });
  });
}

- (void)startupI2PRouter:(ExtractMetaInfo *)metaInfo
{
  std::string basePath([metaInfo.i2pBase UTF8String]);
  auto buildClassPath = [](std::string basePath) -> std::vector<std::string> {
      return globVector(basePath+std::string("/lib/*.jar"));
  };
    // Expect base to be extracted by now.

    // Get paths
    NSBundle *launcherBundle = [NSBundle mainBundle];
  auto jarList = buildClassPath(basePath);
  std::string classpathStrHead = "-classpath";
  std::string classpathStr = "";
  classpathStr += [[launcherBundle pathForResource:@"launcher" ofType:@"jar"] UTF8String];
  std::string prefix(basePath);
  prefix += "/lib/";
  for_each(jarList, [&classpathStr](std::string str){ classpathStr += std::string(":") + str; });
  //if (self.enableVerboseLogging) NSLog(@"Classpath: %@\n",[NSString stringWithUTF8String:classpathStr.c_str()]);

  try {
    auto argList = JavaRunner::defaultStartupFlags;

    std::string baseDirArg("-Di2p.dir.base=");
    baseDirArg += basePath;
    std::string javaLibArg("-Djava.library.path=");
    javaLibArg += basePath;
    // TODO: pass this to JVM
    auto java_opts = getenv("JAVA_OPTS");

    argList.push_back([NSString stringWithUTF8String:baseDirArg.c_str()]);
    argList.push_back([NSString stringWithUTF8String:javaLibArg.c_str()]);
    argList.push_back([NSString stringWithUTF8String:classpathStrHead.c_str()]);
    argList.push_back([NSString stringWithUTF8String:classpathStr.c_str()]);
    argList.push_back(@"net.i2p.router.Router");
    auto javaBin = std::string([metaInfo.javaBinary UTF8String]);


    sendUserNotification(APP_IDSTR, @"I2P Router is starting up!", self.contentImage);
    auto nsJavaBin = metaInfo.javaBinary;
    auto nsBasePath = metaInfo.i2pBase;
    NSArray* arrArguments = [NSArray arrayWithObjects:&argList[0] count:argList.size()];
    startupRouter(nsJavaBin, arrArguments, nsBasePath);
    //if (self.enableVerboseLogging) NSLog(@"Defaults: %@", [pref dictionaryRepresentation]);
  } catch (std::exception &err) {
    auto errMsg = [NSString stringWithUTF8String:err.what()];
    NSLog(@"Exception: %@", errMsg);
    sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg], self.contentImage);
  }
}

- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
                               shouldPresentNotification:(NSUserNotification *)notification {
    return YES;
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

- (void)setApplicationDefaultPreferences {
  auto defaultJVMHome = check_output({"/usr/libexec/java_home","-v",DEF_MIN_JVM_VER});
  auto tmpStdStr = std::string(defaultJVMHome.buf.data());
  trim(tmpStdStr);
  auto cfDefaultHome  = CFStringCreateWithCString(NULL, const_cast<const char *>(tmpStdStr.c_str()), kCFStringEncodingUTF8);
  [self.userPreferences registerDefaults:@{
    @"javaHome" : (NSString *)cfDefaultHome,
    @"lastI2PVersion" : (NSString *)CFSTR(DEF_I2P_VERSION),
    @"enableLogging": @true,
    @"enableVerboseLogging": @true,
    @"autoStartRouter": @true
  }];
  if (self.enableVerboseLogging) NSLog(@"Default JVM home preference set to: %@", (NSString *)cfDefaultHome);

  auto dict = [self.userPreferences dictionaryRepresentation];
  [self.userPreferences setPersistentDomain:dict forName:NSAPPDOMAIN];

  CFPreferencesSetMultiple((CFDictionaryRef)dict, NULL, CFAPPDOMAIN, kCFPreferencesCurrentUser, kCFPreferencesCurrentHost);
  CFPreferencesAppSynchronize(kCFPreferencesCurrentApplication);
  //CFPreferencesSetAppValue(@"javaHome", (CFPropertyListRef)cfDefaultHome, kCFPreferencesCurrentUser, kCFPreferencesCurrentHost);

  if (self.enableVerboseLogging) NSLog(@"Default preferences stored!");
}


- (void)applicationDidFinishLaunching:(NSNotification *)aNotification {
  // Init application here
  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:self];
  // Start with user preferences
  self.userPreferences = [NSUserDefaults standardUserDefaults];
  [self setApplicationDefaultPreferences];
  self.enableLogging = [self.userPreferences boolForKey:@"enableLogging"];
  self.enableVerboseLogging = [self.userPreferences boolForKey:@"enableVerboseLogging"];


  // Get paths
  NSBundle *launcherBundle = [NSBundle mainBundle];
  auto iconImage = [launcherBundle pathForResource:@"ItoopieTransparent" ofType:@"png"];
  self.contentImage = [NSImage imageNamed:iconImage];

  gRawJvmList = std::make_shared<std::list<JvmVersionPtr> >(std::list<JvmVersionPtr>());
  // In case we are unbundled, make us a proper UI application
  [NSApp setActivationPolicy:NSApplicationActivationPolicyAccessory];
  [NSApp activateIgnoringOtherApps:YES];
  //auto prefArray = CFPreferencesCopyKeyList(CFAPPDOMAIN, kCFPreferencesCurrentUser, kCFPreferencesCurrentHost);
  //CFShow(prefArray);
  auto javaHomePref = [self.userPreferences stringForKey:@"javaHome"];
  if (self.enableVerboseLogging) NSLog(@"Java home from preferences: %@", javaHomePref);


  // This is the only GUI the user experience on a regular basis.
  self.menuBarCtrl = [[MenuBarCtrl alloc] init];

  NSString *appDomain = [[NSBundle mainBundle] bundleIdentifier];
  if (self.enableVerboseLogging) NSLog(@"Appdomain is: %@", appDomain);

  NSLog(@"We should have started the statusbar object by now...");

  // Figure out base directory
  const char* pathFromHome = "/Users/%s/Library/I2P";
  auto username = getenv("USER");
  char buffer[strlen(pathFromHome)+strlen(username)];
  sprintf(buffer, pathFromHome, username);
  std::string i2pBaseDir(buffer);
  if (self.enableVerboseLogging) printf("Home directory is: %s\n", buffer);


  //[statusBarButton setAction:@selector(itemClicked:)];
  //dispatch_async(dispatch_get_main_queue(), ^{
  //});
  auto pref = self.userPreferences;
  self.menuBarCtrl.userPreferences = self.userPreferences;
  self.menuBarCtrl.enableLogging = self.enableLogging;
  self.menuBarCtrl.enableVerboseLogging = self.enableVerboseLogging;

  if (self.enableVerboseLogging) NSLog(@"processinfo %@", [[NSProcessInfo processInfo] arguments]);

  auto getJavaHomeLambda = [&pref,&self]() -> std::string {
      NSString* val = @"";
      val = [pref stringForKey:@"javaHome"];
      if (val == NULL) val = @"";
      if (self.enableVerboseLogging) NSLog(@"Javahome: %@", val);
      return std::string([val UTF8String]);;
  };

  auto getJavaBin = [&getJavaHomeLambda]() -> std::string {
      // Get Java home
    auto javaHome = getJavaHomeLambda();
    trim(javaHome); // Trim to remove endline
    auto javaBin = std::string(javaHome);
    javaBin += "/bin/java"; // Append java binary to path.
    return javaBin;
  };

  auto buildClassPath = [](std::string basePath) -> std::vector<std::string> {
      return globVector(basePath+std::string("/lib/*.jar"));
  };


  auto metaInfo = [ExtractMetaInfo alloc];
  metaInfo.i2pBase = [NSString stringWithUTF8String:buffer];
  metaInfo.javaBinary = [NSString stringWithUTF8String:getJavaBin().c_str()];
  metaInfo.jarFile = [launcherBundle pathForResource:@"launcher" ofType:@"jar"];
  metaInfo.zipFile = [launcherBundle pathForResource:@"base" ofType:@"zip"];

  std::string basearg("-Di2p.dir.base=");
  basearg += i2pBaseDir;

  std::string jarfile("-cp ");
  jarfile += [metaInfo.zipFile UTF8String];

  struct stat sb;
  if ( !(stat(buffer, &sb) == 0 && S_ISDIR(sb.st_mode)) )
  {
    // I2P is not extracted.
    if (self.enableVerboseLogging) NSLog(@"I2P Directory don't exists!");

    [self extractI2PBaseDir: metaInfo completion:^(BOOL success, NSError *error) {
        //__typeof__(self) strongSelf = weakSelf;
        //if (strongSelf == nil) return;
        [self startupI2PRouter:metaInfo];
    }];

  } else {
      if (self.enableVerboseLogging) NSLog(@"I2P directory found!");
      [self startupI2PRouter:metaInfo];
  }

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
  NSAutoreleasePool * pool = [[NSAutoreleasePool alloc] init];



  app.delegate = [[AppDelegate alloc] initWithArgc:argc argv:argv];
  [NSBundle loadNibNamed:@"I2Launcher" owner:NSApp];

  [NSApp run];
  // Handle any errors
  //CFRelease(javaHomes);
  //CFRelease(err);
  [pool drain];
  return 0;
}



