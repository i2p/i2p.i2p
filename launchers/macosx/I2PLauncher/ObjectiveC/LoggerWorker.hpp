//
//  LoggerWorker.hpp
//  I2PLauncher
//
//  Created by Mikal Villa on 27/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//  Imported/Refactored from earlier C++ project of Meeh
//

#ifndef LoggerWorker_hpp
#define LoggerWorker_hpp

#ifdef __cplusplus

#include <memory>
#include <thread>
#include <mutex>
#include <future>
#include <string>
#include <condition_variable>
#include <functional>

// TODO: Configure the project to avoid such includes.
#include "../include/sharedqueue.h"
#include "Logger.h"

struct SharedLogWorkerImpl;


namespace MeehLog {
  typedef std::function<void()> Callback;
  
  class Active {
  private:
    Active(const Active&);
    Active& operator=(const Active&);
    Active();                         // Construction ONLY through factory createActive();
    void doDone(){mDone = true;}
    void run();
    
    shared_queue<Callback> mMq;
    std::thread mThd;
    bool mDone;  // finished flag to be set through msg queue by ~Active
    
    
  public:
    virtual ~Active();
    void send(Callback msg_);
    static std::unique_ptr<Active> createActive(); // Factory: safe construction & thread start
  };
  
  
  
  // A straightforward technique to move around packaged_tasks.
  //  Instances of std::packaged_task are MoveConstructible and MoveAssignable, but
  //  not CopyConstructible or CopyAssignable. To put them in a std container they need
  //  to be wrapped and their internals "moved" when tried to be copied.
  template<typename Moveable>
  struct MoveOnCopy {
    mutable Moveable _move_only;
    
    explicit MoveOnCopy(Moveable&& m) : _move_only(std::move(m)) {}
    MoveOnCopy(MoveOnCopy const& t) : _move_only(std::move(t._move_only)) {}
    MoveOnCopy(MoveOnCopy&& t) : _move_only(std::move(t._move_only)) {}
    
    MoveOnCopy& operator=(MoveOnCopy const& other) {
      _move_only = std::move(other._move_only);
      return *this;
    }
    
    MoveOnCopy& operator=(MoveOnCopy&& other) {
      _move_only = std::move(other._move_only);
      return *this;
    }
    
    void operator()() { _move_only(); }
    Moveable& get() { return _move_only; }
    Moveable release() { return std::move(_move_only); }
  };
  
  // Generic helper function to avoid repeating the steps for managing
  // asynchronous task job (by active object) that returns a future results
  // could of course be made even more generic if done more in the way of
  // std::async, ref: http://en.cppreference.com/w/cpp/thread/async
  //
  // Example usage:
  //  std::unique_ptr<Active> bgWorker{Active::createActive()};
  //  ...
  //  auto msg_call=[=](){return ("Hello from the Background");};
  //  auto future_msg = g2::spawn_task(msg_lambda, bgWorker.get());
  
  template <typename Func>
  std::future<typename std::result_of<Func()>::type> spawn_task(Func func, Active* worker) {
    typedef typename std::result_of<Func()>::type result_type;
    typedef std::packaged_task<result_type()> task_type;
    task_type task(std::move(func));
    std::future<result_type> result = task.get_future();
    
    worker->send(MoveOnCopy<task_type>(std::move(task)));
    return std::move(result);
  }
}

class SharedLogWorker {
public:
  /**
   * \param log_prefix is the 'name' of the binary, this give the log name 'LOG-'name'-...
   * \param log_directory gives the directory to put the log files */
  SharedLogWorker(const std::string& log_prefix, const std::string& log_directory);
  virtual ~SharedLogWorker();
  
  /// pushes in background thread (asynchronously) input messages to log file
  void save(const MeehLog::internal::LogEntry& entry);
  
  /// Will push a fatal message on the queue, this is the last message to be processed
  /// this way it's ensured that all existing entries were flushed before 'fatal'
  /// Will abort the application!
  void fatal(MeehLog::internal::FatalMessage fatal_message);
  
  /// Attempt to change the current log file to another name/location.
  /// returns filename with full path if successful, else empty string
  std::future<std::string> changeLogFile(const std::string& log_directory);
  
  /// Does an independent action in FIFO order, compared to the normal LOG statements
  /// Example: auto threadID = [] { std::cout << "thread id: " << std::this_thread::get_id() << std::endl; };
  ///          auto call = logger.genericAsyncCall(threadID);
  ///          // this will print out the thread id of the background worker
  std::future<void> genericAsyncCall(std::function<void()> f);
  
  /// Probably only needed for unit-testing or specific log management post logging
  /// request to get log name is processed in FIFO order just like any other background job.
  std::future<std::string> logFileName();
  
private:
  std::unique_ptr<SharedLogWorkerImpl> pimpl;
  const std::string logFileWithPath;
  
  SharedLogWorker(const SharedLogWorker&);
  SharedLogWorker& operator=(const SharedLogWorker&);
};

#endif // __cplusplus

#endif /* LoggerWorker_hpp */
