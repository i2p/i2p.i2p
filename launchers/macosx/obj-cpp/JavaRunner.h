#pragma once

#include <dispatch/dispatch.h>
#include <functional>
#include <memory>
#include <string>
#include <list>

#include <subprocess.hpp>
#include <optional.hpp>

using namespace subprocess;
using namespace std::experimental;

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

  const std::list<std::string> defaultStartupFlags {
    "-Xmx512M",
    "-Xms128m",
    "-Djava.awt.headless=true",
    "-Dwrapper.logfile=/tmp/router.log",
    "-Dwrapper.logfile.loglevel=DEBUG",
    "-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
    "-Dwrapper.console.loglevel=DEBUG",
    "-Di2p.dir.base=$BASEPATH",
    "-Djava.library.path=$BASEPATH",
    "$JAVA_OPTS",
    "net.i2p.launchers.SimpleOSXLauncher"
  };

  const std::list<std::string> defaultFlagsForExtractorJob {
    "-Xmx512M",
    "-Xms128m",
    "-Djava.awt.headless=true",
    "-Di2p.dir.base=$BASEPATH",
    "-Di2p.dir.zip=$ZIPPATH",
    "net.i2p.launchers.BaseExtractor",
    "extract"
  };

  optional<std::future<int> > execute();
  std::shared_ptr<Popen> javaProcess;
  std::string javaBinaryPath;
private:
  const fp_proc_t& executingFn;
  const fp_t& exitCallbackFn;
};

