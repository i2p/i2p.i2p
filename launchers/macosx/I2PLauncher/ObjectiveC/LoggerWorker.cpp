//
//  LoggerWorker.cpp
//  I2PLauncher
//
//  Created by Mikal Villa on 27/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//  Imported/Refactored from earlier C++ project of Meeh
//

#include "LoggerWorker.hpp"
#include "Logger.h"

#include <fstream>
#include <sstream>
#include <cassert>
#include <algorithm>
#include <string>
#include <chrono>
#include <functional>
#include <future>

using namespace MeehLog;
using namespace MeehLog::internal;

Active::Active(): mDone(false){}

Active::~Active() {
  Callback quit_token = std::bind(&Active::doDone, this);
  send(quit_token); // tell thread to exit
  mThd.join();
}

// Add asynchronously a work-message to queue
void Active::send(Callback msg_){
  mMq.push(msg_);
}


// Will wait for msgs if queue is empty
// A great explanation of how this is done (using Qt's library):
// http://doc.qt.nokia.com/stable/qwaitcondition.html
void Active::run() {
  while (!mDone) {
    // wait till job is available, then retrieve it and
    // executes the retrieved job in this thread (background)
    Callback func;
    mMq.wait_and_pop(func);
    func();
  }
}

// Factory: safe construction of object before thread start
std::unique_ptr<Active> Active::createActive(){
  std::unique_ptr<Active> aPtr(new Active());
  aPtr->mThd = std::thread(&Active::run, aPtr.get());
  return aPtr;
}

namespace {
  static const std::string date_formatted =  "%Y/%m/%d";
  static const std::string time_formatted = "%H:%M:%S";
  static const std::string file_name_time_formatted =  "%Y%m%d-%H%M%S";
  
  // check for filename validity -  filename should not be part of PATH
  bool isValidFilename(const std::string prefix_filename) {
    
    std::string illegal_characters("/,|<>:#$%{}()[]\'\"^!?+* ");
    size_t pos = prefix_filename.find_first_of(illegal_characters, 0);
    if (pos != std::string::npos) {
      std::cerr << "Illegal character [" << prefix_filename.at(pos) << "] in logname prefix: " << "[" << prefix_filename << "]" << std::endl;
      return false;
    } else if (prefix_filename.empty()) {
      std::cerr << "Empty filename prefix is not allowed" << std::endl;
      return false;
    }
    
    return true;
  }

  // Clean up the path if put in by mistake in the prefix
  std::string prefixSanityFix(std::string prefix) {
    prefix.erase(std::remove_if(prefix.begin(), prefix.end(), ::isspace), prefix.end());
    prefix.erase(std::remove( prefix.begin(), prefix.end(), '\\'), prefix.end()); // '\\'
    prefix.erase(std::remove( prefix.begin(), prefix.end(), '.'), prefix.end());  // '.'
    prefix.erase(std::remove(prefix.begin(), prefix.end(), ':'), prefix.end()); // ':'
    
    if (!isValidFilename(prefix)) {
      return "";
    }
    return prefix;
  }
  std::string pathSanityFix(std::string path, std::string file_name) {
    // Unify the delimeters,. maybe sketchy solution but it seems to work
    // on at least win7 + ubuntu. All bets are off for older windows
    std::replace(path.begin(), path.end(), '\\', '/');
    
    // clean up in case of multiples
    auto contains_end = [&](std::string & in) -> bool {
      size_t size = in.size();
      if (!size) return false;
      char end = in[size - 1];
      return (end == '/' || end == ' ');
    };
    
    while (contains_end(path)) { path.erase(path.size() - 1); }
    if (!path.empty()) {
      path.insert(path.end(), '/');
    }
    path.insert(path.size(), file_name);
    return path;
  }
  
  
  std::string createLogFileName(const std::string& verified_prefix) {
    std::stringstream ossName;
    ossName.fill('0');
    ossName << verified_prefix << MeehLog::localtime_formatted(systemtime_now(), file_name_time_formatted);
    ossName << ".log";
    return ossName.str();
  }
  
  
  bool openLogFile(const std::string& complete_file_with_path, std::ofstream& outstream) {
    std::ios_base::openmode mode = std::ios_base::out; // for clarity: it's really overkill since it's an ofstream
    mode |= std::ios_base::trunc;
    outstream.open(complete_file_with_path, mode);
    if (!outstream.is_open()) {
      std::ostringstream ss_error;
      ss_error << "FILE ERROR:  could not open log file:[" << complete_file_with_path << "]";
      ss_error << "\nstd::ios_base state = " << outstream.rdstate();
      std::cerr << ss_error.str().c_str() << std::endl << std::flush;
      outstream.close();
      return false;
    }
    std::ostringstream ss_entry;
    //  Day Month Date Time Year: is written as "%a %b %d %H:%M:%S %Y" and formatted output as : Wed Aug 10 08:19:45 2014
    ss_entry << "MeehLog created log file at: " << MeehLog::localtime_formatted(systemtime_now(), "%a %b %d %H:%M:%S %Y") << "\n";
    ss_entry << "LOG format: [YYYY/MM/DD hh:mm:ss uuu* LEVEL FILE:LINE] message (uuu*: microsecond counter since initialization of log worker)\n\n";
    outstream << ss_entry.str() << std::flush;
    outstream.fill('0');
    return true;
  }
  
  
  std::unique_ptr<std::ofstream> createLogFile(const std::string& file_with_full_path) {
    std::unique_ptr<std::ofstream> out(new std::ofstream);
    std::ofstream& stream(*(out.get()));
    bool success_with_open_file = openLogFile(file_with_full_path, stream);
    if (false == success_with_open_file) {
      out.release(); // nullptr contained ptr<file> signals error in creating the log file
    }
    return out;
  }
}  // end anonymous namespace

/** The Real McCoy Background worker, while g2LogWorker gives the
 * asynchronous API to put job in the background the g2LogWorkerImpl
 * does the actual background thread work */
struct SharedLogWorkerImpl {
  SharedLogWorkerImpl(const std::string& log_prefix, const std::string& log_directory);
  ~SharedLogWorkerImpl();
  
  void backgroundFileWrite(MeehLog::internal::LogEntry message);
  void backgroundExitFatal(MeehLog::internal::FatalMessage fatal_message);
  std::string  backgroundChangeLogFile(const std::string& directory);
  std::string  backgroundFileName();
  
  std::string log_file_with_path_;
  std::string log_prefix_backup_; // needed in case of future log file changes of directory
  std::unique_ptr<MeehLog::Active> mBg;
  std::unique_ptr<std::ofstream> mOutptr;
  steady_time_point steady_start_time_;
  
private:
  SharedLogWorkerImpl& operator=(const SharedLogWorkerImpl&);
  SharedLogWorkerImpl(const SharedLogWorkerImpl& other);
  std::ofstream& filestream() {return *(mOutptr.get());}
};

//
// Private API implementation : SharedLogWorkerImpl
SharedLogWorkerImpl::SharedLogWorkerImpl(const std::string& log_prefix, const std::string& log_directory)
: log_file_with_path_(log_directory)
, log_prefix_backup_(log_prefix)
, mBg(MeehLog::Active::createActive())
, mOutptr(new std::ofstream)
, steady_start_time_(std::chrono::steady_clock::now()) { // TODO: ha en timer function steadyTimer som har koll på start
  log_prefix_backup_ = prefixSanityFix(log_prefix);
  if (!isValidFilename(log_prefix_backup_)) {
    // illegal prefix, refuse to start
    std::cerr << "MeehLog: forced abort due to illegal log prefix [" << log_prefix << "]" << std::endl << std::flush;
    abort();
  }
  
  std::string file_name = createLogFileName(log_prefix_backup_);
  log_file_with_path_ = pathSanityFix(log_file_with_path_, file_name);
  mOutptr = createLogFile(log_file_with_path_);
  if (!mOutptr) {
    std::cerr << "Cannot write logfile to location, attempting current directory" << std::endl;
    log_file_with_path_ = "./" + file_name;
    mOutptr = createLogFile(log_file_with_path_);
  }
  assert((mOutptr) && "cannot open log file at startup");
}


SharedLogWorkerImpl::~SharedLogWorkerImpl() {
  std::ostringstream ss_exit;
  mBg.reset(); // flush the log queue
  ss_exit << "\nMeehLog file shutdown at: " << MeehLog::localtime_formatted(systemtime_now(), time_formatted);
  filestream() << ss_exit.str() << std::flush;
}


void SharedLogWorkerImpl::backgroundFileWrite(LogEntry message) {
  using namespace std;
  std::ofstream& out(filestream());
  auto log_time = message.mTimestamp;
  auto steady_time = std::chrono::steady_clock::now();
  out << "\n" << MeehLog::localtime_formatted(log_time, date_formatted);
  out << " " << MeehLog::localtime_formatted(log_time, time_formatted);
  out << " " << chrono::duration_cast<std::chrono::microseconds>(steady_time - steady_start_time_).count();
  out << "\t" << message.mMsg << std::flush;
}


void SharedLogWorkerImpl::backgroundExitFatal(FatalMessage fatal_message) {
  backgroundFileWrite(fatal_message.mMessage);
  LogEntry flushEntry{"Log flushed successfully to disk \nExiting", MeehLog::internal::systemtime_now()};
  backgroundFileWrite(flushEntry);
  std::cerr << "MeehLog exiting after receiving fatal event" << std::endl;
  std::cerr << "Log file at: [" << log_file_with_path_ << "]\n" << std::endl << std::flush;
  filestream().close();
  MeehLog::shutDownLogging(); // only an initialized logger can recieve a fatal message. So shutting down logging now is fine.
  //exitWithDefaultSignalHandler(fatal_message.mSignalId);
  perror("MeehLog exited after receiving FATAL trigger. Flush message status: "); // should never reach this point
}


std::string SharedLogWorkerImpl::backgroundChangeLogFile(const std::string& directory) {
  std::string file_name = createLogFileName(log_prefix_backup_);
  std::string prospect_log = directory + file_name;
  std::unique_ptr<std::ofstream> log_stream = createLogFile(prospect_log);
  if (nullptr == log_stream) {
    LogEntry error("Unable to change log file. Illegal filename or busy? Unsuccessful log name was:" + prospect_log, MeehLog::internal::systemtime_now());
    backgroundFileWrite(error);
    
    return ""; // no success
  }
  
  std::ostringstream ss_change;
  ss_change << "\nChanging log file from : " << log_file_with_path_;
  ss_change << "\nto new location: " << prospect_log << "\n";
  backgroundFileWrite({ss_change.str().c_str(), MeehLog::internal::systemtime_now()});
  ss_change.str("");
  
  // setting the new log as active
  std::string old_log = log_file_with_path_;
  log_file_with_path_ = prospect_log;
  mOutptr = std::move(log_stream);
  ss_change << "\nNew log file. The previous log file was at: ";
  ss_change << old_log;
  backgroundFileWrite({ss_change.str(), MeehLog::internal::systemtime_now()});
  return log_file_with_path_;
}

std::string  SharedLogWorkerImpl::backgroundFileName() {
  return log_file_with_path_;
}


// Public API implementation
//
SharedLogWorker::SharedLogWorker(const std::string& log_prefix, const std::string& log_directory)
:  pimpl(new SharedLogWorkerImpl(log_prefix, log_directory))
, logFileWithPath(pimpl->log_file_with_path_) {
  assert((pimpl != nullptr) && "shouild never happen");
}

SharedLogWorker::~SharedLogWorker() {
  pimpl.reset();
  MeehLog::shutDownLoggingForActiveOnly(this);
  std::cerr << "\nExiting, log location: " << logFileWithPath << std::endl << std::flush;
}

void SharedLogWorker::save(const MeehLog::internal::LogEntry& msg) {
  pimpl->mBg->send(std::bind(&SharedLogWorkerImpl::backgroundFileWrite, pimpl.get(), msg));
}

void SharedLogWorker::fatal(MeehLog::internal::FatalMessage fatal_message) {
  pimpl->mBg->send(std::bind(&SharedLogWorkerImpl::backgroundExitFatal, pimpl.get(), fatal_message));
}


#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wpessimizing-move"

std::future<std::string> SharedLogWorker::changeLogFile(const std::string& log_directory) {
  MeehLog::Active* bgWorker = pimpl->mBg.get();
  auto mBgcall =     [this, log_directory]() {return pimpl->backgroundChangeLogFile(log_directory);};
  auto future_result = MeehLog::spawn_task(mBgcall, bgWorker);
  return std::move(future_result);
}


std::future<void> SharedLogWorker::genericAsyncCall(std::function<void()> f) {
  auto bgWorker = pimpl->mBg.get();
  auto future_result = MeehLog::spawn_task(f, bgWorker);
  return std::move(future_result);
}

std::future<std::string> SharedLogWorker::logFileName() {
  MeehLog::Active* bgWorker = pimpl->mBg.get();
  auto mBgcall = [&]() {return pimpl->backgroundFileName();};
  auto future_result = MeehLog::spawn_task(mBgcall , bgWorker);
  return std::move(future_result);
}

#pragma GCC diagnostic pop

