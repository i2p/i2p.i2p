#include "RouterTask.h"

#include <dispatch/dispatch.h>
#include <future>
#include <stdlib.h>

#ifdef __cplusplus
#include "include/subprocess.hpp"
#import "I2PLauncher-Swift.h"
#include "AppDelegate.h"
#endif

#include "include/PidWatcher.h"

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>

@implementation RTaskOptions
@end

@implementation I2PRouterTask


- (void)routerStdoutData:(NSNotification *)notification
{
    NSLog(@"%@", [[NSString alloc] initWithData:[notification.object availableData] encoding:NSUTF8StringEncoding]);
    [notification.object waitForDataInBackgroundAndNotify];
}

- (instancetype) initWithOptions : (RTaskOptions*) options
{
  self.userRequestedRestart = NO;
  self.isRouterRunning = NO;
  //self.input = [NSFileHandle fileHandleWithStandardInput];
  self.routerTask = [NSTask new];
  self.processPipe = [NSPipe new];
  [self.routerTask setLaunchPath:options.binPath];
  [self.routerTask setArguments:options.arguments];
  NSDictionary *envDict = @{
    @"I2PBASE": options.i2pBaseDir
  };
  [self.routerTask setEnvironment: envDict];
  NSLog(@"Using environment variables: %@", envDict);
  [self.routerTask setStandardOutput:self.processPipe];
	[self.routerTask setStandardError:self.processPipe];

  [self.routerTask setTerminationHandler:^(NSTask* task) {
    // Cleanup
    NSLog(@"termHandler triggered!");
    auto swiftRouterStatus = [[RouterProcessStatus alloc] init];
    [swiftRouterStatus setRouterStatus: false];
    [swiftRouterStatus setRouterRanByUs: false];
    [swiftRouterStatus triggerEventWithEn:@"router_stop" details:@"normal shutdown"];
    [[SBridge sharedInstance] setCurrentRouterInstance:nil];
    sendUserNotification(APP_IDSTR, @"I2P Router has stopped");
  }];
    return self;
}

- (void) requestShutdown
{
    [self.routerTask interrupt];
}

- (void) requestRestart
{
    self.userRequestedRestart = YES;
    kill([self.routerTask processIdentifier], SIGHUP);
}

- (BOOL) isRunning
{
    return self.routerTask.running;
}

- (int) execute
{
    @try {
      [self.routerTask launch];
      self.isRouterRunning = YES;
      return 1;
    }
    @catch (NSException *e)
	{
		NSLog(@"Expection occurred %@", [e reason]);
    auto swiftRouterStatus = [[RouterProcessStatus alloc] init];
    self.isRouterRunning = NO;
    [swiftRouterStatus setRouterStatus: false];
    [swiftRouterStatus setRouterRanByUs: false];
    [swiftRouterStatus triggerEventWithEn:@"router_stop" details:@"error shutdown"];
    [[SBridge sharedInstance] setCurrentRouterInstance:nil];
    sendUserNotification(@"An error occured, can't start the I2P Router", [e reason]);
    return 0;
	}
}

- (int) getPID
{
  return [self.routerTask processIdentifier];
}

@end
