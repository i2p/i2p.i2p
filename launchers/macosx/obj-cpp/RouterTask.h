#pragma once

#include <dispatch/dispatch.h>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include <Cocoa/Cocoa.h>
#import <AppKit/AppKit.h>

#include "optional.hpp"
#include "subprocess.hpp"


@class RTaskOptions;

@interface RTaskOptions : NSObject
@property (strong) NSString* binPath;
@property (strong) NSArray<NSString *>* arguments;
@property (strong) NSString* i2pBaseDir;
@end

@class RouterTask;

@interface RouterTask : NSObject
@property (strong) NSTask* routerTask;
@property (strong) NSUserDefaults *userPreferences;
@property (strong) NSFileHandle *readLogHandle;
@property (strong) NSMutableData *totalLogData;
@property (strong) NSPipe *processPipe;
@property (strong) NSFileHandle *input;
@property (atomic) BOOL userRequestedRestart;
- (instancetype) initWithOptions : (RTaskOptions*) options;
- (int) execute;
- (void) requestShutdown;
- (void) requestRestart;
- (BOOL) isRunning;
- (int) getPID;
@end



using namespace subprocess;

class JavaRunner;

typedef std::function<void(void)> fp_t;
typedef std::function<void(JavaRunner *ptr)> fp_proc_t;

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

  static const std::vector<NSString*> defaultStartupFlags;
  static const std::vector<std::string> defaultFlagsForExtractorJob;

  void requestRouterShutdown();

  std::experimental::optional<std::future<int> > execute();
  std::shared_ptr<Popen> javaProcess;
  std::string javaBinaryPath;
  std::string javaRouterArgs;
  std::string execLine;
  std::string _i2pBaseDir;
private:
  const fp_proc_t& executingFn;
  const fp_t& exitCallbackFn;
};


