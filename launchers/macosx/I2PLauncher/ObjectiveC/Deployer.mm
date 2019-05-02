//
//  Deployer.m
//  I2PLauncher
//
//  Created by Mikal Villa on 19/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//
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
// TODO: Configure the project to avoid such includes.
#include "../include/fn.h"
#include "../include/subprocess.hpp"
#include "../include/strutil.hpp"

#import "SBridge.h"
#include "logger_c.h"

#include <string>


#include "Logger.h"
#include "LoggerWorker.hpp"

#import "Deployer.h"

#include <string.h>

using namespace subprocess;

@implementation I2PDeployer

- (I2PDeployer *) initWithMetaInfo:(ExtractMetaInfo*)mi
{
  self.metaInfo = mi;
  return self;
}

- (void) extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion
{
#ifdef __cplusplus
  NSBundle *launcherBundle = [NSBundle mainBundle];
  auto homeDir = RealHomeDirectory();
  NSLog(@"Home directory is %s", homeDir);
  
  std::string basePath(homeDir);
  basePath.append("/Library/I2P");
  
  self.metaInfo.zipFile = [launcherBundle pathForResource:@"base" ofType:@"zip"];
  
  NSParameterAssert(basePath.c_str());
  NSError *error = NULL;
  BOOL success = YES;
  dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
    
    
    try {
      // Create directory
      mkdir(basePath.c_str(), S_IRUSR | S_IWUSR | S_IXUSR);
      
      auto cli = defaultFlagsForExtractorJob;
      setenv("I2PBASE", basePath.c_str(), true);
      //setenv("DYLD_LIBRARY_PATH",".:/usr/lib:/lib:/usr/local/lib", true);
      
      chdir(basePath.c_str());
      
      // Everything behind --exec java - would be passed as arguments to the java executable.
      std::string execStr = "/usr/bin/unzip -uo "; //std::string([rs.getJavaHome UTF8String]);
      execStr += [self.metaInfo.zipFile UTF8String];
      //for_each(cli, [&execStr](std::string str){ execStr += std::string(" ") + str; });
      
      NSLog(@"Trying cmd: %@", [NSString stringWithUTF8String:execStr.c_str()]);
      sendUserNotification(APP_IDSTR, @"Please hold on while we extract I2P. You'll get a new message once done!");
      int extractStatus = Popen(execStr.c_str(), environment{{
        {"I2PBASE", basePath.c_str()}
      }}).wait();
      NSLog(@"Extraction exit code %@",[NSString stringWithUTF8String:(std::to_string(extractStatus)).c_str()]);
      if (extractStatus == 0) {
        NSLog(@"Extraction process done");
      } else {
        NSLog(@"Something went wrong");
      }
      
      // All done. Assume success and error are already set.
      dispatch_async(dispatch_get_main_queue(), ^{
        if (completion) {
          completion(YES, error);
        }
      });
      
      
    } catch (OSError &err) {
      auto errMsg = [NSString stringWithUTF8String:err.what()];
      NSLog(@"Exception: %@", errMsg);
      sendUserNotification(APP_IDSTR, [NSString stringWithFormat:@"Error: %@", errMsg]);
    }
  });
#endif
}

@end

