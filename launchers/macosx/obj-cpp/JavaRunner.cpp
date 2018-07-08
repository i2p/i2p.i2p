#include "JavaRunner.h"

#include <dispatch/dispatch.h>
#include <subprocess.hpp>
#include <future>

using namespace subprocess;
using namespace std::experimental;

JavaRunner::JavaRunner(std::string javaBin, const fp_proc_t& execFn, const fp_t& cb)
  : javaBinaryPath(javaBin), executingFn(execFn), exitCallbackFn(cb)
{
  javaProcess = std::shared_ptr<Popen>(new Popen({javaBin.c_str(), "-version"}, defer_spawn{true}));
}

optional<std::future<int> > JavaRunner::execute()
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
    return nullopt;
  }
}
