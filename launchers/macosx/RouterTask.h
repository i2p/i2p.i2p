#pragma once

#include <dispatch/dispatch.h>
#include <memory.h>

#include <Cocoa/Cocoa.h>
#import <AppKit/AppKit.h>

#ifdef __cplusplus
#include <vector>
#include <string>

const std::vector<NSString*> defaultStartupFlags {
  @"-Xmx512M",
  @"-Xms128m",
  @"-Djava.awt.headless=true",
  @"-Dwrapper.logfile=/tmp/router.log",
  @"-Dwrapper.logfile.loglevel=DEBUG",
  @"-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
  @"-Dwrapper.console.loglevel=DEBUG"
};

const std::vector<std::string> defaultFlagsForExtractorJob {
  "-Xmx512M",
  "-Xms128m",
  "-Djava.awt.headless=true"
};

#endif


@class RTaskOptions;
@interface RTaskOptions : NSObject
@property (strong) NSString* binPath;
@property (strong) NSArray<NSString *>* arguments;
@property (strong) NSString* i2pBaseDir;
@end

@class I2PRouterTask;
@interface I2PRouterTask : NSObject
@property (strong) NSTask* routerTask;
@property (strong) NSPipe *processPipe;
@property (atomic) BOOL isRouterRunning;
@property (atomic) BOOL userRequestedRestart;
- (instancetype) initWithOptions : (RTaskOptions*) options;
- (int) execute;
- (void) requestShutdown;
- (void) requestRestart;
- (BOOL) isRunning;
- (int) getPID;
- (void)routerStdoutData:(NSNotification *)notification;
@end






