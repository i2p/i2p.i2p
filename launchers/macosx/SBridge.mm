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

#include "AppDelegate.h"
#include "include/fn.h"



std::future<int> startupRouter(NSString* javaBin, NSArray<NSString*>* arguments, NSString* i2pBaseDir) {
  @try {
    RTaskOptions* options = [RTaskOptions alloc];
    options.binPath = javaBin;
    options.arguments = arguments;
    options.i2pBaseDir = i2pBaseDir;
    auto instance = [[I2PRouterTask alloc] initWithOptions: options];
    //setGlobalRouterObject(instance);
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

namespace osx {
  inline void openUrl(NSString* url)
  {
    [[NSWorkspace sharedWorkspace] openURL:[NSURL URLWithString: url]];
  }
}

inline std::vector<std::string> globVector(const std::string& pattern){
  glob_t glob_result;
  glob(pattern.c_str(),GLOB_TILDE,NULL,&glob_result);
  std::vector<std::string> files;
  for(unsigned int i=0;i<glob_result.gl_pathc;++i){
    files.push_back(std::string(glob_result.gl_pathv[i]));
  }
  globfree(&glob_result);
  return files;
}

inline std::string buildClassPathForObjC(std::string basePath)
{
  NSBundle *launcherBundle = [NSBundle mainBundle];
  auto jarList = globVector(basePath+std::string("/lib/*.jar"));
  
  std::string classpathStrHead = "-classpath";
  std::string classpathStr = "";
  classpathStr += [[launcherBundle pathForResource:@"launcher" ofType:@"jar"] UTF8String];
  std::string prefix(basePath);
  prefix += "/lib/";
  for_each(jarList, [&classpathStr](std::string str){ classpathStr += std::string(":") + str; });
  return classpathStr;
}


@implementation SBridge


- (void) openUrl:(NSString*)url
{
  osx::openUrl(url);
}

- (NSString*) buildClassPath:(NSString*)i2pPath
{
  const char * basePath = [i2pPath UTF8String];
  auto jarList = buildClassPathForObjC(basePath);
  const char * classpath = jarList.c_str();
  NSLog(@"Classpath from ObjC = %s", classpath);
  return [[NSString alloc] initWithUTF8String:classpath];
}



- (void)startupI2PRouter:(NSString*)i2pRootPath javaBinPath:(NSString*)javaBinPath
{
  std::string basePath([i2pRootPath UTF8String]);
  
  // Get paths
  //NSBundle *launcherBundle = [NSBundle mainBundle];
  auto classPathStr = buildClassPathForObjC(basePath);
  
  RouterProcessStatus* routerStatus = [[RouterProcessStatus alloc] init];
  try {
    std::vector<NSString*> argList = {
      @"-Xmx512M",
      @"-Xms128m",
      @"-Djava.awt.headless=true",
      @"-Dwrapper.logfile=/tmp/router.log",
      @"-Dwrapper.logfile.loglevel=DEBUG",
      @"-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
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
    auto javaBin = std::string([javaBinPath UTF8String]);
    
    
    sendUserNotification(APP_IDSTR, @"I2P Router is starting up!");
    auto nsJavaBin = javaBinPath;
    auto nsBasePath = i2pRootPath;
    NSArray* arrArguments = [NSArray arrayWithObjects:&argList[0] count:argList.size()];
    // We don't really know yet, but per now a workaround
    [routerStatus setRouterStatus: true];
    NSLog(@"Trying to run command: %@", javaBinPath);
    NSLog(@"With I2P Base dir: %@", i2pRootPath);
    NSLog(@"And Arguments: %@", arrArguments);
    startupRouter(nsJavaBin, arrArguments, nsBasePath);
  } catch (std::exception &err) {
    auto errMsg = [NSString stringWithUTF8String:err.what()];
    NSLog(@"Exception: %@", errMsg);
    sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg]);
    [routerStatus setRouterStatus: false];
    [routerStatus setRouterRanByUs: false];
  }
}
@end


#endif
