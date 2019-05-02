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
// TODO: Configure the project to avoid such includes.
#include "../include/fn.h"


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

+ (void) logProxy:(int)level formattedMsg:(NSString*)formattedMsg
{
  MLog(level, formattedMsg);
}

@end


#endif
