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

  /*
  NSFileHandle *stdoutFileHandle = [self.processPipe fileHandleForReading];
  dup2([[self.processPipe fileHandleForWriting] fileDescriptor], fileno(stdout));
  auto source = dispatch_source_create(DISPATCH_SOURCE_TYPE_READ, [stdoutFileHandle fileDescriptor], 0, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0));
  dispatch_source_set_event_handler(source, ^{
    void* data = malloc(4096);
    ssize_t readResult = 0;
    do
    {
      errno = 0;
      readResult = read([stdoutFileHandle fileDescriptor], data, 4096);
    } while (readResult == -1 && errno == EINTR);
    if (readResult > 0)
    {
      //AppKit UI should only be updated from the main thread
      dispatch_async(dispatch_get_main_queue(),^{
        NSString* stdOutString = [[NSString alloc] initWithBytesNoCopy:data length:readResult encoding:NSUTF8StringEncoding freeWhenDone:YES];
        NSAttributedString* stdOutAttributedString = [[NSAttributedString alloc] initWithString:stdOutString];
        NSLog(@"Router stdout: %@", stdOutString);
        //auto logForwarder = new LogForwarder();
        //[logForwarder appendLogViewWithLogLine:stdOutAttributedString];
      });
    }
    else{free(data);}
  });
  dispatch_resume(source);
  */
  /*
  [[NSNotificationCenter defaultCenter] addObserver:self
      selector:@selector(routerStdoutData:)
      name:NSFileHandleDataAvailableNotification
      object:stdoutFileHandle];

  [stdoutFileHandle waitForDataInBackgroundAndNotify];
  */

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
      auto swiftRouterStatus = [[RouterProcessStatus alloc] init];
      [swiftRouterStatus triggerEventWithEn:@"router_start" details:@"normal start"];
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
