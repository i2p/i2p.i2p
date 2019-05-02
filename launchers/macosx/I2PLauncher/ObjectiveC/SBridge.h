//
//  SBridge.h
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <Cocoa/Cocoa.h>

#import "RouterTask.h"

#ifdef __cplusplus
#include <memory>
#include <future>
#include <glob.h>
#include <string>
#include <vector>
// TODO: Configure the project to avoid such includes.
#include "../include/fn.h"

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

#endif


@interface SBridge : NSObject
@property (nonatomic, assign) I2PRouterTask* currentRouterInstance;
- (void) openUrl:(NSString*)url;
+ (void) logProxy:(int)level formattedMsg:(NSString*)formattedMsg;
+ (void) sendUserNotification:(NSString*)title formattedMsg:(NSString*)formattedMsg;
+ (instancetype)sharedInstance; // this makes it a singleton
@end
