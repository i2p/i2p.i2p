#include "RouterTask.h"

#include <dispatch/dispatch.h>
#include <future>
#include <stdlib.h>

#ifdef __cplusplus
// TODO: Configure the project to avoid such includes.
#include "../include/subprocess.hpp"
#include "../include/PidWatcher.h"

#import "I2PLauncher-Swift.h"
#include "AppDelegate.h"

#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <sys/sysctl.h>
#endif


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
    [[[RouterProcessStatus alloc] init] triggerEventWithEn:@"router_stop" details:@"normal shutdown"];
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

{    self.userRequestedRestart = YES;
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
    self.isRouterRunning = NO;
    
    [[[RouterProcessStatus alloc] init] triggerEventWithEn:@"router_exception" details:[e reason]];
    
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

typedef struct kinfo_proc kinfo_proc;

@implementation IIProcessInfo
- (id) init
{
  self = [super init];
  if (self != nil)
  {
    numberOfProcesses = -1; // means "not initialized"
    processList = NULL;
  }
  return self;
}

- (int)numberOfProcesses
{
  return numberOfProcesses;
}

- (void)setNumberOfProcesses:(int)num
{
  numberOfProcesses = num;
}

- (int)getBSDProcessList:(kinfo_proc **)procList
   withNumberOfProcesses:(size_t *)procCount
{
#ifdef __cplusplus
  int             err;
  kinfo_proc *    result;
  bool            done;
  static const int    name[] = { CTL_KERN, KERN_PROC, KERN_PROC_ALL, 0 };
  size_t          length;
  
  // a valid pointer procList holder should be passed
  assert( procList != NULL );
  // But it should not be pre-allocated
  assert( *procList == NULL );
  // a valid pointer to procCount should be passed
  assert( procCount != NULL );
  
  *procCount = 0;
  result = NULL;
  done = false;
  
  do
  {
    assert( result == NULL );
    
    // Call sysctl with a NULL buffer to get proper length
    length = 0;
    err = sysctl((int *)name,(sizeof(name)/sizeof(*name))-1,NULL,&length,NULL,0);
    if( err == -1 )
      err = errno;
    
    // Now, proper length is optained
    if( err == 0 )
    {
      result = (kinfo_proc *)malloc(length);
      if( result == NULL )
        err = ENOMEM;   // not allocated
    }
    
    if( err == 0 )
    {
      err = sysctl( (int *)name, (sizeof(name)/sizeof(*name))-1, result, &length, NULL, 0);
      if( err == -1 )
        err = errno;
      
      if( err == 0 )
        done = true;
      else if( err == ENOMEM )
      {
        assert( result != NULL );
        free( result );
        result = NULL;
        err = 0;
      }
    }
  }while ( err == 0 && !done );
  
  // Clean up and establish post condition
  if( err != 0 && result != NULL )
  {
    free(result);
    result = NULL;
  }
  
  *procList = result; // will return the result as procList
  if( err == 0 )
    *procCount = length / sizeof( kinfo_proc );
  assert( (err == 0) == (*procList != NULL ) );
  return err;
}

- (void)obtainFreshProcessList
{
  int i;
  kinfo_proc *allProcs = 0;
  size_t numProcs;
  NSString *procName;
  
  int err =  [self getBSDProcessList:&allProcs withNumberOfProcesses:&numProcs];
  if( err )
  {
    numberOfProcesses = -1;
    processList = NULL;
    
    return;
  }
  
  // Construct an array for ( process name, pid, arguments concat'ed )
  processList = [NSMutableArray arrayWithCapacity:numProcs];
  for( i = 0; i < numProcs; i++ )
  {
    int pid = (int)allProcs[i].kp_proc.p_pid;
    procName = [NSString stringWithFormat:@"%s, pid %d, args: %s", allProcs[i].kp_proc.p_comm, pid, getArgvOfPid(pid).c_str()];
    [processList addObject:procName];
  }
  
  [self setNumberOfProcesses:(int)numProcs];
  free( allProcs );
#endif
}


- (BOOL)findProcessWithStringInNameOrArguments:(NSString *)procNameToSearch
{
  BOOL seenProcessThatMatch = NO;
  for (NSString* processInfoStr in processList) {
    if ([processInfoStr containsString:procNameToSearch]) {
      seenProcessThatMatch = YES;
      break;
    }
  }
  return seenProcessThatMatch;
}
@end
