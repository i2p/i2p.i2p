#pragma once

#include <dispatch/dispatch.h>
#include <memory.h>
#include <string.h>

#include <Cocoa/Cocoa.h>
#import <AppKit/AppKit.h>

#ifdef __cplusplus
#include "include/subprocess.hpp"

using namespace subprocess;
class JavaRunner;

typedef std::function<void(void)> fp_t;
typedef std::function<void(JavaRunner *ptr)> fp_proc_t;

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

/**
 *
 * class JavaRunner
 *
 **/
class JavaRunner
{
public:
  // copy fn
  JavaRunner(std::string& javaBin, std::string& arguments, std::string& i2pBaseDir, const fp_proc_t& executingFn, const fp_t& cb);
  ~JavaRunner() = default;
  
  void requestRouterShutdown();
  
  std::future<int> execute();
  std::shared_ptr<subprocess::Popen> javaProcess;
  std::string javaBinaryPath;
  std::string javaRouterArgs;
  std::string execLine;
  std::string _i2pBaseDir;
private:
  const fp_proc_t& executingFn;
  const fp_t& exitCallbackFn;
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
@property (strong) NSUserDefaults *userPreferences;
@property (strong) NSFileHandle *readLogHandle;
@property (strong) NSMutableData *totalLogData;
@property (strong) NSPipe *processPipe;
@property (strong) NSFileHandle *input;
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






