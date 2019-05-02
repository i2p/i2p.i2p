#pragma once

#include <dispatch/dispatch.h>
#include <memory.h>
#include <sys/sysctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

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


@interface IIProcessInfo : NSObject {
  
@private
  int numberOfProcesses;
  NSMutableArray *processList;
}
- (id) init;
- (int)numberOfProcesses;
- (void)obtainFreshProcessList;
- (BOOL)findProcessWithStringInNameOrArguments:(NSString *)procNameToSearch;
@end

#ifdef __cplusplus

// Inspired by the "ps" util.

inline std::string getArgvOfPid(int pid) {
  int    mib[3], argmax, nargs, c = 0;
  size_t    size;
  char    *procargs, *sp, *np, *cp;
  int show_args = 1;
  mib[0] = CTL_KERN;
  mib[1] = KERN_ARGMAX;
  
  size = sizeof(argmax);
  if (sysctl(mib, 2, &argmax, &size, NULL, 0) == -1) {
    return std::string("sorry");
  }
  
  /* Allocate space for the arguments. */
  procargs = (char *)malloc(argmax);
  if (procargs == NULL) {
    return std::string("sorry");
  }
  /*
   * Make a sysctl() call to get the raw argument space of the process.
   * The layout is documented in start.s, which is part of the Csu
   * project.  In summary, it looks like:
   *
   * /---------------\ 0x00000000
   * :               :
   * :               :
   * |---------------|
   * | argc          |
   * |---------------|
   * | arg[0]        |
   * |---------------|
   * :               :
   * :               :
   * |---------------|
   * | arg[argc - 1] |
   * |---------------|
   * | 0             |
   * |---------------|
   * | env[0]        |
   * |---------------|
   * :               :
   * :               :
   * |---------------|
   * | env[n]        |
   * |---------------|
   * | 0             |
   * |---------------| <-- Beginning of data returned by sysctl() is here.
   * | argc          |
   * |---------------|
   * | exec_path     |
   * |:::::::::::::::|
   * |               |
   * | String area.  |
   * |               |
   * |---------------| <-- Top of stack.
   * :               :
   * :               :
   * \---------------/ 0xffffffff
   */
  mib[0] = CTL_KERN;
  mib[1] = KERN_PROCARGS2;
  mib[2] = pid;
  
  
  size = (size_t)argmax;
  if (sysctl(mib, 3, procargs, &size, NULL, 0) == -1) {
    free(procargs);
    return std::string("sorry");
  }
  
  memcpy(&nargs, procargs, sizeof(nargs));
  cp = procargs + sizeof(nargs);
  
  /* Skip the saved exec_path. */
  for (; cp < &procargs[size]; cp++) {
    if (*cp == '\0') {
      /* End of exec_path reached. */
      break;
    }
  }
  if (cp == &procargs[size]) {
    free(procargs);
    return std::string("sorry");
  }
  
  /* Skip trailing '\0' characters. */
  for (; cp < &procargs[size]; cp++) {
    if (*cp != '\0') {
      /* Beginning of first argument reached. */
      break;
    }
  }
  if (cp == &procargs[size]) {
    free(procargs);
    return std::string("sorry");
  }
  /* Save where the argv[0] string starts. */
  sp = cp;
  
  /*
   * Iterate through the '\0'-terminated strings and convert '\0' to ' '
   * until a string is found that has a '=' character in it (or there are
   * no more strings in procargs).  There is no way to deterministically
   * know where the command arguments end and the environment strings
   * start, which is why the '=' character is searched for as a heuristic.
   */
  for (np = NULL; c < nargs && cp < &procargs[size]; cp++) {
    if (*cp == '\0') {
      c++;
      if (np != NULL) {
        /* Convert previous '\0'. */
        *np = ' ';
      } else {
        /* *argv0len = cp - sp; */
      }
      /* Note location of current '\0'. */
      np = cp;
      
      if (!show_args) {
        /*
         * Don't convert '\0' characters to ' '.
         * However, we needed to know that the
         * command name was terminated, which we
         * now know.
         */
        break;
      }
    }
  }
  
  /*
   * sp points to the beginning of the arguments/environment string, and
   * np should point to the '\0' terminator for the string.
   */
  if (np == NULL || np == sp) {
    /* Empty or unterminated string. */
    free(procargs);
    return std::string("sorry");
  }
  return std::string(sp);
}

#endif
