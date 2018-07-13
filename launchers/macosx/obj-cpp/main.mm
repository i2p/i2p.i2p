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

#define DEF_I2P_VERSION "0.9.35"
#define APPDOMAIN "net.i2p.launcher"
#define NSAPPDOMAIN @APPDOMAIN
#define CFAPPDOMAIN CFSTR(APPDOMAIN)

#define debug(format, ...) CFShow([NSString stringWithFormat:format, ## __VA_ARGS__]);

JvmListSharedPtr gRawJvmList = nullptr;


@interface MenuBarCtrl () <StatusItemButtonDelegate, NSMenuDelegate>
@end

@interface AppDelegate () <NSUserNotificationCenterDelegate, NSApplicationDelegate>
@end


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
    CFShow(arguments);

    @try {
        RTaskOptions* options = [RTaskOptions alloc];
        options.binPath = javaBin;
        options.arguments = arguments;
        options.i2pBaseDir = i2pBaseDir;
        auto instance = [[[RouterTask alloc] initWithOptions: options] autorelease];
        //auto pid = [instance execute];
        //NSThread *thr = [[NSThread alloc] initWithTarget:instance selector:@selector(execute) object:nil];
        [instance execute];
        return std::async(std::launch::async, [&instance]{
          return 1;//[instance getPID];
        });
    }
    @catch (NSException *e)
	{
		NSLog(@"Expection occurred %@", [e reason]);
        return std::async(std::launch::async, [&]{
          return 0;
        });
	}
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

- (void) startJavaRouterBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked startJavaRouterBtnHandler");
}

- (void) restartJavaRouterBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked restartJavaRouterBtnHandler");
}

- (void) stopJavaRouterBtnHandler: (NSMenuItem *) menuItem
{
  NSLog(@"Clicked stopJavaRouterBtnHandler");
  if (getGlobalRouterObject().has_value())
  {
      //getGlobalRouterObject().value()->requestRouterShutdown();
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


  [menu addItem:startI2Pbtn];
  [menu addItem:stopI2Pbtn];
  [menu addItem:restartI2Pbtn];
  [menu addItem:quitWrapperBtn];
  return menu;
}

@end


@implementation AppDelegate

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

  auto sendUserNotification = [&](NSString* title, NSString* informativeText) -> void {
    NSUserNotification *userNotification = [[[NSUserNotification alloc] init] autorelease];

    userNotification.title = title;
    userNotification.informativeText = informativeText;
    userNotification.soundName = NSUserNotificationDefaultSoundName;

    [[NSUserNotificationCenter defaultUserNotificationCenter] scheduleNotification:userNotification];
  };


  // Get paths
  NSBundle *launcherBundle = [NSBundle mainBundle];

  std::string basearg("-Di2p.dir.base=");
  basearg += i2pBaseDir;

  std::string zippath("-Di2p.base.zip=");
  zippath += [[launcherBundle pathForResource:@"base" ofType:@"zip"] UTF8String];

  std::string jarfile("-cp ");
  jarfile += [[launcherBundle pathForResource:@"launcher" ofType:@"jar"] UTF8String];

  struct stat sb;
  if ( !(stat(buffer, &sb) == 0 && S_ISDIR(sb.st_mode)) )
  {
    // I2P is not extracted.
    if (self.enableVerboseLogging) printf("I2P Directory don't exists!\n");

    // Create directory
    mkdir(buffer, S_IRUSR | S_IWUSR | S_IXUSR);

    auto cli = JavaRunner::defaultFlagsForExtractorJob;
    setenv("I2PBASE", buffer, true);
    setenv("ZIPPATH", zippath.c_str(), true);
    //setenv("DYLD_LIBRARY_PATH",".:/usr/lib:/lib:/usr/local/lib", true);

    cli.push_back(basearg);
    cli.push_back(zippath);
    cli.push_back(jarfile);
    cli.push_back("net.i2p.launchers.BaseExtractor");

    //auto charCli = map(cli, [](std::string str){ return str.c_str(); });
    std::string execStr = getJavaBin();
    for_each(cli, [&execStr](std::string str){ execStr += std::string(" ") + str; });

    printf("\n\nTrying cmd: %s\n\n", execStr.c_str());
    try {
        sendUserNotification((NSString*)CFSTR("I2P Extraction"), (NSString*)CFSTR("Please hold on while we extract I2P. You'll get a new message once done!"));
        int extractStatus = Popen(execStr.c_str(), environment{{
            {"ZIPPATH", zippath.c_str()},
            {"I2PBASE", buffer}
        }}).wait();
        printf("Extraction exit code %d\n",extractStatus);
        sendUserNotification((NSString*)CFSTR("I2P Extraction"), (NSString*)CFSTR("Extraction complete!"));
    } catch (subprocess::OSError &err) {
        printf("Something bad happened: %s\n", err.what());
    }

  } else {
      if (self.enableVerboseLogging) printf("I2P directory found!\n");
  }

  // Expect base to be extracted by now.

  auto jarList = buildClassPath(std::string(buffer));
  std::string classpathStrHead = "-classpath";
  std::string classpathStr = "";
  classpathStr += [[launcherBundle pathForResource:@"launcher" ofType:@"jar"] UTF8String];
  std::string prefix(i2pBaseDir);
  prefix += "/lib/";
  for_each(jarList, [&classpathStr](std::string str){ classpathStr += std::string(":") + str; });
  //if (self.enableVerboseLogging) NSLog(@"Classpath: %@\n",[NSString stringWithUTF8String:classpathStr.c_str()]);



  try {
    auto argList = JavaRunner::defaultStartupFlags;

    std::string baseDirArg("-Di2p.dir.base=");
    baseDirArg += i2pBaseDir;
    std::string javaLibArg("-Djava.library.path=");
    javaLibArg += i2pBaseDir;
    // TODO: pass this to JVM
    auto java_opts = getenv("JAVA_OPTS");

    argList.push_back([NSString stringWithUTF8String:baseDirArg.c_str()]);
    argList.push_back([NSString stringWithUTF8String:javaLibArg.c_str()]);
    argList.push_back([NSString stringWithUTF8String:classpathStrHead.c_str()]);
    argList.push_back([NSString stringWithUTF8String:classpathStr.c_str()]);
    argList.push_back(@"net.i2p.router.Router");
    auto javaBin = getJavaBin();


    sendUserNotification(@"I2P Launcher", @"I2P Router is starting up!");
    auto nsJavaBin = [NSString stringWithUTF8String:javaBin.c_str()];
    auto nsBasePath = [NSString stringWithUTF8String:i2pBaseDir.c_str()];
    NSArray* arrArguments = [NSArray arrayWithObjects:&argList[0] count:argList.size()];
    startupRouter(nsJavaBin, arrArguments, nsBasePath);
    //if (self.enableVerboseLogging) NSLog(@"Defaults: %@", [pref dictionaryRepresentation]);
  } catch (std::exception &err) {
    std::cerr << "Exception: " << err.what() << std::endl;
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



