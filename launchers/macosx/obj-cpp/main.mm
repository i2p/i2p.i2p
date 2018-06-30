#include <functional>
#include <memory>
#include <iostream>
#include <algorithm>
#include <string>
#include <list>
#include <experimental/optional>

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
#include "JavaRunner.h"
#include "JavaHelper.h"

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

  [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:self];

  // This is the only GUI the user experience on a regular basis.
  self.menuBarCtrl = [[MenuBarCtrl alloc] init];

  NSString *appDomain = [[NSBundle mainBundle] bundleIdentifier];
  if (self.enableVerboseLogging) NSLog(@"Appdomain is: %@", appDomain);

  NSLog(@"We should have started the statusbar object by now...");
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


  auto launchLambda = [&pref](JavaRunner *javaRun) {
    javaRun->javaProcess->start_process();
    auto pid = javaRun->javaProcess->pid();
    std::cout << "I2P Router process id = " << pid << std::endl;

    // Blocking
    javaRun->javaProcess->wait();
  };
  auto callbackAfterExit = [=](){
    printf("Callback after exit\n");
  };

  try {

    // Get Java home
    auto javaHome = getJavaHomeLambda();
    trim(javaHome); // Trim to remove endline
    auto javaBin = std::string(javaHome);
    javaBin += "/bin/java"; // Append java binary to path.
    //printf("hello world: %s\n", javaBin.c_str());
    if (self.enableVerboseLogging) NSLog(@"Defaults: %@", [pref dictionaryRepresentation]);

    auto r = new JavaRunner{ javaBin, launchLambda, callbackAfterExit };
    r->execute();
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



