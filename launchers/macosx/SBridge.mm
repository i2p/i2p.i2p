//
//  SBridge.m
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

#import "SBridge.h"

#ifdef __cplusplus
#include <functional>
#include <memory>
#include <glob.h>
#include <string>
#include <list>
#include <stdlib.h>
#include <future>
#include <vector>

#import <AppKit/AppKit.h>
#import "I2PLauncher-Swift.h"
#include "LoggerWorker.hpp"
#include "Logger.h"
#include "logger_c.h"

#include "AppDelegate.h"
#include "include/fn.h"



std::future<int> startupRouter(NSString* javaBin, NSArray<NSString*>* arguments, NSString* i2pBaseDir, RouterProcessStatus* routerStatus) {
  @try {
    
    /**
     *
     * The following code will do a test, where it lists all known processes in the OS (visibility depending on user rights)
     * and scan for any command/arguments matching the substring "i2p.jar" - and in which case it won't start I2P itself.
     *
     **/
    IIProcessInfo* processInfoObj = [[IIProcessInfo alloc] init];
    [processInfoObj obtainFreshProcessList];
    auto anyRouterLookingProcs = [processInfoObj findProcessWithStringInNameOrArguments:@"i2p.jar"];
    if (anyRouterLookingProcs) {
      /**
       * The router was found running
       */
      auto errMessage = @"Seems i2p is already running - I've detected another process with i2p.jar in it's arguments.";
      MLog(4, @"%@", errMessage);
      sendUserNotification(APP_IDSTR, errMessage);
      [routerStatus triggerEventWithEn:@"router_already_running" details:@"won't start - another router is running"];
      return std::async(std::launch::async, []{
        return -1;
      });
    } else {
      /**
       * No router was detected running
       **/
      RTaskOptions* options = [RTaskOptions alloc];
      options.binPath = javaBin;
      options.arguments = arguments;
      options.i2pBaseDir = i2pBaseDir;
      auto instance = [[I2PRouterTask alloc] initWithOptions: options];
      
      [[SBridge sharedInstance] setCurrentRouterInstance:instance];
      [instance execute];
      sendUserNotification(APP_IDSTR, @"The I2P router is starting up.");
      auto pid = [instance getPID];
      MLog(2, @"Got pid: %d", pid);
      if (routerStatus != nil) {
        // TODO: Merge events router_start and router_pid ?
        [routerStatus triggerEventWithEn:@"router_start" details:@"normal start"];
        [routerStatus triggerEventWithEn:@"router_pid" details:[NSString stringWithFormat:@"%d", pid]];
      }
      NSString *applicationSupportDirectory = [NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES) firstObject];
      auto pidFile = [NSURL fileURLWithPath:[NSString stringWithFormat:@"%@/i2p/router.pid", applicationSupportDirectory]];
      NSError *err;
      
      if (![[NSString stringWithFormat:@"%d", pid] writeToURL:pidFile atomically:YES encoding:NSUTF8StringEncoding error:&err]) {
        MLog(4, @"Error; %@", err);
      } else {
        MLog(3, @"Wrote pid file to %@", pidFile);
      }
      
      return std::async(std::launch::async, [&pid]{
        return pid;
      });
    }
  }
  @catch (NSException *e)
  {
    auto errStr = [NSString stringWithFormat:@"Expection occurred %@",[e reason]];
    MLog(4, @"%@", errStr);
    sendUserNotification(APP_IDSTR, errStr);
    [[SBridge sharedInstance] setCurrentRouterInstance:nil];
    
    if (routerStatus != nil) {
      [routerStatus triggerEventWithEn:@"router_exception" details:errStr];
    }
    
    return std::async(std::launch::async, [&]{
      return 0;
    });
  }
}



@implementation SBridge

// this makes it a singleton
+ (instancetype)sharedInstance {
  static SBridge *sharedInstance = nil;
  static dispatch_once_t onceToken;
  
  dispatch_once(&onceToken, ^{
    sharedInstance = [[SBridge alloc] init];
  });
  return sharedInstance;
}

+ (void) sendUserNotification:(NSString*)title formattedMsg:(NSString*)formattedMsg
{
  sendUserNotification(title, formattedMsg);
}

- (void) openUrl:(NSString*)url
{
  osx::openUrl(url);
}

- (NSString*) buildClassPath:(NSString*)i2pPath
{
  const char * basePath = [i2pPath UTF8String];
  auto jarList = buildClassPathForObjC(basePath);
  const char * classpath = jarList.c_str();
  MLog(0, @"Classpath from ObjC = %s", classpath);
  return [[NSString alloc] initWithUTF8String:classpath];
}

+ (void) logProxy:(int)level formattedMsg:(NSString*)formattedMsg
{
  MLog(level, formattedMsg);
}


- (void)startupI2PRouter:(NSString*)i2pRootPath
{
  std::string basePath([i2pRootPath UTF8String]);
  
  auto classPathStr = buildClassPathForObjC(basePath);
  
  RouterProcessStatus* routerStatus = [[RouterProcessStatus alloc] init];
  
  NSString *confDir = [NSString stringWithFormat:@"%@/Library/Application\\ Support/i2p", NSHomeDirectory()];
  
  try {
    std::vector<NSString*> argList = {
      @"-v",
      @"1.7+",
      @"--exec",
      @"java",
      @"-Xmx512M",
      @"-Xms128m",
      @"-Djava.awt.headless=true",
      [NSString stringWithFormat:@"-Dwrapper.logfile=%@/router.log", [NSString stringWithUTF8String:getDefaultLogDir().c_str()]],
      @"-Dwrapper.logfile.loglevel=DEBUG",
      [NSString stringWithFormat:@"-Dwrapper.java.pidfile=%@/router.pid", confDir],
      @"-Dwrapper.console.loglevel=DEBUG"
    };
    
    std::string baseDirArg("-Di2p.dir.base=");
    baseDirArg += basePath;
    std::string javaLibArg("-Djava.library.path=");
    javaLibArg += basePath;
    // TODO: pass this to JVM
    //auto java_opts = getenv("JAVA_OPTS");
    
    std::string cpString = std::string("-cp");
    
    argList.push_back([NSString stringWithUTF8String:baseDirArg.c_str()]);
    argList.push_back([NSString stringWithUTF8String:javaLibArg.c_str()]);
    argList.push_back([NSString stringWithUTF8String:cpString.c_str()]);
    argList.push_back([NSString stringWithUTF8String:classPathStr.c_str()]);
    argList.push_back(@"net.i2p.router.Router");
    auto javaBin = std::string("/usr/libexec/java_home");
    
    
    sendUserNotification(APP_IDSTR, @"I2P Router is starting up!");
    auto nsJavaBin = [NSString stringWithUTF8String:javaBin.c_str()];
    auto nsBasePath = i2pRootPath;
    NSArray* arrArguments = [NSArray arrayWithObjects:&argList[0] count:argList.size()];
    
    MLog(0, @"Trying to run command: %@", nsJavaBin);
    MLog(0, @"With I2P Base dir: %@", i2pRootPath);
    MLog(0, @"And Arguments: %@", arrArguments);
    startupRouter(nsJavaBin, arrArguments, nsBasePath, routerStatus);
  } catch (std::exception &err) {
    auto errMsg = [NSString stringWithUTF8String:err.what()];
    MLog(4, @"Exception: %@", errMsg);
    sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg]);
    [routerStatus setRouterStatus: false];
    [routerStatus setRouterRanByUs: false];
    [routerStatus triggerEventWithEn:@"router_exception" details:[NSString stringWithFormat:@"Error: %@", errMsg]];
  }
}
@end


#endif
