#include "RouterTask.h"

#include <dispatch/dispatch.h>
#include <future>
#include <stdlib.h>

#include "optional.hpp"
#include "subprocess.hpp"
#include "PidWatcher.h"

#import <AppKit/AppKit.h>

@implementation RTaskOptions
@end

@implementation RouterTask


- (instancetype) initWithOptions : (RTaskOptions*) options
{
    self.userRequestedRestart = FALSE;
    self.input = [NSFileHandle fileHandleWithStandardInput];
    self.routerTask = [NSTask new];
    self.processPipe = [NSPipe new];
    [self.routerTask setLaunchPath:options.binPath];
    [self.routerTask setArguments:options.arguments];
    NSDictionary *envDict = @{
        @"I2PBASE": options.i2pBaseDir
    };
    [self.routerTask setEnvironment: envDict];
    [self.routerTask setStandardOutput:self.processPipe];
	[self.routerTask setStandardError:self.processPipe];
    [self.routerTask setTerminationHandler:^(NSTask* task) {
        NSLog(@"termHandler triggered!");
    }];
/*
    self.readLogHandle = [self.processPipe fileHandleForReading];
    NSData *inData = nil;
    self.totalLogData = [[[NSMutableData alloc] init] autorelease];

    while ((inData = [self.readLogHandle availableData]) &&
        [inData length]) {
        [self.totalLogData appendData:inData];
    }
*/
    return self;
}

- (void) requestShutdown
{
    [self.routerTask interrupt];
}

- (void) requestRestart
{
    self.userRequestedRestart = TRUE;
}

- (BOOL) isRunning
{
    return self.routerTask.running;
}

- (int) execute
{
    //@try {
        [self.routerTask launch];
        watchPid([self.routerTask processIdentifier]);
        [self.input waitForDataInBackgroundAndNotify];
        [[self.processPipe fileHandleForReading] waitForDataInBackgroundAndNotify];
        [[NSNotificationCenter defaultCenter] addObserverForName:NSFileHandleDataAvailableNotification
                                                          object:[self.processPipe fileHandleForReading] queue:nil
                                                      usingBlock:^(NSNotification *note)
         {
             // Read from shell output
             NSData *outData = [[self.processPipe fileHandleForReading] availableData];
             NSString *outStr = [[NSString alloc] initWithData:outData encoding:NSUTF8StringEncoding];
             if ([outStr length] > 1) {
                 NSLog(@"output: %@", outStr);
             }

             // Continue waiting for shell output.
             [[self.processPipe fileHandleForReading] waitForDataInBackgroundAndNotify];
         }];
         //[self.routerTask waitUntilExit];
        //NSThread *thr = [[NSThread alloc] initWithTarget:self.routerTask selector:@selector(launch) object:nil];
        //[self.routerTask waitUntilExit];
        return 1;
    /*}
    @catch (NSException *e)
	{
		NSLog(@"Expection occurred %@", [e reason]);
        return 0;
	}*/
}

- (int) getPID
{
    return [self.routerTask processIdentifier];
}

@end




using namespace subprocess;

const std::vector<NSString*> JavaRunner::defaultStartupFlags {
    @"-Xmx512M",
    @"-Xms128m",
    @"-Djava.awt.headless=true",
    @"-Dwrapper.logfile=/tmp/router.log",
    @"-Dwrapper.logfile.loglevel=DEBUG",
    @"-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
    @"-Dwrapper.console.loglevel=DEBUG"
};

const std::vector<std::string> JavaRunner::defaultFlagsForExtractorJob {
    "-Xmx512M",
    "-Xms128m",
    "-Djava.awt.headless=true"
};

JavaRunner::JavaRunner(std::string& javaBin, std::string& arguments, std::string& i2pBaseDir, const fp_proc_t& execFn, const fp_t& cb)
  : javaBinaryPath(javaBin), javaRouterArgs(arguments), _i2pBaseDir(i2pBaseDir), executingFn(execFn), exitCallbackFn(cb)
{
  execLine = javaBinaryPath;
  execLine += " " + std::string(javaRouterArgs.c_str());
  printf("CLI: %s\n",execLine.c_str());
  javaProcess = std::shared_ptr<Popen>(new Popen(execLine, environment{{
            {"I2PBASE", _i2pBaseDir},
            {"JAVA_OPTS", getenv("JAVA_OPTS")}
        }}, defer_spawn{true}));
}

void JavaRunner::requestRouterShutdown()
{
    // SIGHUP
    javaProcess->kill(1);
}

std::experimental::optional<std::future<int> > JavaRunner::execute()
{
  try {
    auto executingFn = dispatch_block_create(DISPATCH_BLOCK_INHERIT_QOS_CLASS, ^{
      this->executingFn(this);
    });
    dispatch_async(dispatch_get_global_queue( DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), executingFn);
    dispatch_block_wait(executingFn, DISPATCH_TIME_FOREVER);

    // Here, the process is done executing.

    printf("Finished executingFn - Runs callbackFn\n");
    this->exitCallbackFn();
    return std::async(std::launch::async, []{ return 0; });
  } catch (std::exception* ex) {
    printf("ERROR: %s\n", ex->what());
    return std::experimental::nullopt;
  }
}
