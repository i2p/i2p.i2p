#pragma once

#include <dispatch/dispatch.h>
#include <functional>
#include <memory>

#include <subprocess.hpp>

using namespace subprocess;

class JavaRunner;

struct CRouterState
{
  enum State {
    C_STOPPED = 0,
    C_STARTED,
    C_RUNNING
  };
};

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
  JavaRunner(std::string javaBin, const fp_proc_t& executingFn, const fp_t& cb);
  ~JavaRunner() = default;

  void execute();
  std::shared_ptr<Popen> javaProcess;
  std::string javaBinaryPath;
private:
  const fp_proc_t& executingFn;
  const fp_t& exitCallbackFn;
};

