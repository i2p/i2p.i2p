//
//  Logger.cpp
//  I2PLauncher
//
//  Created by Mikal Villa on 27/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//  Imported/Refactored from earlier C++ project of Meeh
//

#include <iostream>
#include <sstream>
#include <string>
#include <stdexcept> // exceptions
#include <cstdio>    // vsnprintf
#include <cassert>
#include <mutex>
#include <signal.h>
#include <thread>

#include <unistd.h>
#include <execinfo.h>
#include <cxxabi.h>
#include <cstdlib>
#include <sys/ucontext.h>

#include "Logger.h"
#include "LoggerWorker.hpp"

namespace {
  std::once_flag gIsInitialized;
  std::once_flag gSetFirstUninitializedFlag;
  std::once_flag gSaveFirstUnintializedFlag;
  SharedLogWorker* gLoggerWorkerInstance = nullptr;
  MeehLog::internal::LogEntry gFirstUnintializedMsg = {"", 0};
  std::mutex gLoggerMasterMutex;
  const std::string kTruncatedWarningText = "[...truncated...]";
  
  std::string splitFileName(const std::string& str) {
    size_t found;
    found = str.find_last_of("\\");
    return str.substr(found + 1);
  }
  
  void saveToLogger(const MeehLog::internal::LogEntry& logEntry) {
    // Uninitialized messages are ignored but does not MASSERT/crash the logger
    if (!MeehLog::internal::isLoggingInitialized()) {
      std::string err("LOGGER NOT INITIALIZED: " + logEntry.mMsg);
      std::call_once(gSetFirstUninitializedFlag,
                     [&] { gFirstUnintializedMsg.mMsg += err;
                       gFirstUnintializedMsg.mTimestamp = MeehLog::internal::systemtime_now();
                     });
      // dump to std::err all the non-initialized logs
      std::cerr << err << std::endl;
      return;
    }
    // Save the first uninitialized message, if any
    std::call_once(gSaveFirstUnintializedFlag, [] {
      if (!gFirstUnintializedMsg.mMsg.empty()) {
        gLoggerWorkerInstance->save(gFirstUnintializedMsg);
      }
    });
    
    gLoggerWorkerInstance->save(logEntry);
  }
  void crashHandler(int signal_number, siginfo_t* info, void* unused_context) {
    const size_t max_dump_size = 50;
    void* dump[max_dump_size];
    size_t size = backtrace(dump, max_dump_size);
    char** messages = backtrace_symbols(dump, (int)size); // overwrite sigaction with caller's address
    
    std::ostringstream oss;
    oss << "Received fatal signal: " << MeehLog::internal::signalName(signal_number);
    oss << "(" << signal_number << ")" << std::endl;
    oss << "PID: " << getpid() << std::endl;
    
    // dump stack: skip first frame, since that is here
    for (size_t idx = 1; idx < size && messages != nullptr; ++idx) {
      char* mangled_name = 0, *offset_begin = 0, *offset_end = 0;
      // find parantheses and +address offset surrounding mangled name
      for (char* p = messages[idx]; *p; ++p) {
        if (*p == '(') {
          mangled_name = p;
        } else if (*p == '+') {
          offset_begin = p;
        } else if (*p == ')') {
          offset_end = p;
          break;
        }
      }
      
      // if the line could be processed, attempt to demangle the symbol
      if (mangled_name && offset_begin && offset_end &&
          mangled_name < offset_begin) {
        *mangled_name++ = '\0';
        *offset_begin++ = '\0';
        *offset_end++ = '\0';
        
        int status;
        char* real_name = abi::__cxa_demangle(mangled_name, 0, 0, &status);
        // if demangling is successful, output the demangled function name
        if (status == 0) {
          oss << "stack dump [" << idx << "]  " << messages[idx] << " : " << real_name << "+";
          oss << offset_begin << offset_end << std::endl;
        }
        // otherwise, output the mangled function name
        else {
          oss << "stack dump [" << idx << "]  " << messages[idx] << mangled_name << "+";
          oss << offset_begin << offset_end << std::endl;
        }
        free(real_name); // mallocated by abi::__cxa_demangle(...)
      } else {
        // no demangling done -- just dump the whole line
        oss << "stack dump [" << idx << "]  " << messages[idx] << std::endl;
      }
    } // END: for(size_t idx = 1; idx < size && messages != nullptr; ++idx)
    
    
    
    free(messages);
    {
      // Local scope, trigger send
      using namespace MeehLog::internal;
      std::ostringstream fatal_stream;
      fatal_stream << "\n\n***** FATAL TRIGGER RECEIVED ******* " << std::endl;
      fatal_stream << oss.str() << std::endl;
      fatal_stream << "\n***** RETHROWING SIGNAL " << signalName(signal_number) << "(" << signal_number << ")" << std::endl;
      
      LogEntry entry = {fatal_stream.str(), systemtime_now()};
      FatalMessage fatal_message(entry, FatalMessage::kReasonOS_FATAL_SIGNAL, signal_number);
      FatalTrigger trigger(fatal_message);  std::ostringstream oss;
      std::cerr << fatal_message.mMessage.mMsg << std::endl << std::flush;
    } // message sent to SharedLogWorker
    // wait to die -- will be inside the FatalTrigger
  }
} // End anonymous namespace



namespace MeehLog {
  
  // signalhandler and internal clock is only needed to install once
  // for unit testing purposes the initializeLogging might be called
  // several times... for all other practical use, it shouldn't!
  void initializeLogging(SharedLogWorker* bgworker) {
    std::call_once(gIsInitialized, []() {
      //installSignalHandler();
    });
    
    std::lock_guard<std::mutex> lock(gLoggerMasterMutex);
    MASSERT(!internal::isLoggingInitialized());
    MASSERT(bgworker != nullptr);
    gLoggerWorkerInstance = bgworker;
  }
  
  
  
  void  shutDownLogging() {
    std::lock_guard<std::mutex> lock(gLoggerMasterMutex);
    gLoggerWorkerInstance = nullptr;
  }
  
  
  
  bool shutDownLoggingForActiveOnly(SharedLogWorker* active) {
    if (internal::isLoggingInitialized() && nullptr != active  &&
        (dynamic_cast<void*>(active) != dynamic_cast<void*>(gLoggerWorkerInstance))) {
      MLOG(WARN) << "\nShutting down logging, but the ID of the Logger is not the one that is active."
      << "\nHaving multiple instances of the MeehLog::LogWorker is likely a BUG"
      << "\nEither way, this call to shutDownLogging was ignored";
      return false;
    }
    shutDownLogging();
    return true;
  }
  
  void installSignalHandler() {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = &crashHandler;  // callback to crashHandler for fatal signals
    // sigaction to use sa_sigaction file. ref: http://www.linuxprogrammingblog.com/code-examples/sigaction
    action.sa_flags = SA_SIGINFO;
    
    // do it verbose style - install all signal actions
    if (sigaction(SIGABRT, &action, NULL) < 0)
      perror("sigaction - SIGABRT");
    if (sigaction(SIGFPE, &action, NULL) < 0)
      perror("sigaction - SIGFPE");
    if (sigaction(SIGILL, &action, NULL) < 0)
      perror("sigaction - SIGILL");
    if (sigaction(SIGSEGV, &action, NULL) < 0)
      perror("sigaction - SIGSEGV");
    if (sigaction(SIGTERM, &action, NULL) < 0)
      perror("sigaction - SIGTERM");
  }
  
  namespace internal {
    
    std::time_t systemtime_now()
    {
      system_time_point system_now = std::chrono::system_clock::now();
      return std::chrono::system_clock::to_time_t(system_now);
    }
    
    bool isLoggingInitialized() {
      return gLoggerWorkerInstance != nullptr;
    }
    
    LogContractMessage::LogContractMessage(const std::string& file, const int line,
                                           const std::string& function, const std::string& boolean_expression)
    : LogMessage(file, line, function, "FATAL")
    , mExpression(boolean_expression)
    {}
    
    LogContractMessage::~LogContractMessage() {
      std::ostringstream oss;
      if (0 == mExpression.compare(kFatalLogExpression)) {
        oss << "\n[  *******\tEXIT trigger caused by MLOG(FATAL): \n";
      } else {
        oss << "\n[  *******\tEXIT trigger caused by broken Contract: MASSERT(" << mExpression << ")\n";
      }
      mLogEntry = oss.str();
    }
    
    LogMessage::LogMessage(const std::string& file, const int line, const std::string& function, const std::string& level)
    : mFile(file)
    , mLine(line)
    , mFunction(function)
    , mLevel(level)
    , mTimestamp(systemtime_now())
    {}
    
    LogMessage::~LogMessage() {
      using namespace internal;
      std::ostringstream oss;
      const bool fatal = (0 == mLevel.compare("FATAL"));
      oss << mLevel << " [" << splitFileName(mFile);
      if (fatal)
        oss <<  " at function: " << mFunction ;
        oss << " Line: " << mLine << "]";
      
      const std::string str(mStream.str());
      if (!str.empty()) {
        oss << '"' << str << '"';
      }
      mLogEntry += oss.str();
      
      
      if (fatal) { // os_fatal is handled by crashhandlers
        {
          // local scope - to trigger FatalMessage sending
          FatalMessage::FatalType fatal_type(FatalMessage::kReasonFatal);
          FatalMessage fatal_message({mLogEntry, mTimestamp}, fatal_type, SIGABRT);
          FatalTrigger trigger(fatal_message);
          std::cerr  << mLogEntry << "\t*******  ]" << std::endl << std::flush;
        } // will send to worker
      }
      
      saveToLogger({mLogEntry, mTimestamp}); // message saved
    }
    
    // represents the actual fatal message
    FatalMessage::FatalMessage(LogEntry message, FatalType type, int signal_id)
    : mMessage(message)
    , mType(type)
    , mSignalId(signal_id) {}
    
    
    FatalMessage& FatalMessage::operator=(const FatalMessage& other) {
      mMessage = other.mMessage;
      mType = other.mType;
      mSignalId = other.mSignalId;
      return *this;
    }
    
    std::string signalName(int signal_number) {
      switch (signal_number) {
        case SIGABRT: return "SIGABRT"; break;
        case SIGFPE:  return "SIGFPE"; break;
        case SIGSEGV: return "SIGSEGV"; break;
        case SIGILL:  return "SIGILL"; break;
        case SIGTERM: return "SIGTERM"; break;
        default:
          std::ostringstream oss;
          oss << "UNKNOWN SIGNAL(" << signal_number << ")";
          return oss.str();
      }
    }
    
    void exitWithDefaultSignalHandler(int signal_number) {
      std::cerr << "Exiting - FATAL SIGNAL: " << signal_number << "   " << std::flush;
      struct sigaction action;
      memset(&action, 0, sizeof(action));  //
      sigemptyset(&action.sa_mask);
      action.sa_handler = SIG_DFL; // take default action for the signal
      sigaction(signal_number, &action, NULL);
      kill(getpid(), signal_number);
      abort(); // should never reach this
    }
    
    /** Fatal call saved to logger. This will trigger SIGABRT or other fatal signal
     * to exit the program. After saving the fatal message the calling thread
     * will sleep forever (i.e. until the background thread catches up, saves the fatal
     * message and kills the software with the fatal signal.
     */
    void fatalCallToLogger(FatalMessage message) {
      if (!isLoggingInitialized()) {
        std::ostringstream error;
        error << "FATAL CALL but logger is NOT initialized\n"
        << "SIGNAL: " << MeehLog::internal::signalName(message.mSignalId)
        << "\nMessage: \n" << message.mMessage.mMsg << std::flush;
        std::cerr << error.str();
        
        //internal::exitWithDefaultSignalHandler(message.mSignalId);
      }
      gLoggerWorkerInstance->fatal(message);
      while (true) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
      }
    }
    
    std::function<void(FatalMessage) > gFatalToMLogWorkerFunctionPtr = fatalCallToLogger;
    
    // used to RAII trigger fatal message sending to g2LogWorker
    FatalTrigger::FatalTrigger(const FatalMessage& message)
    :  mMessage(message) {}
    
    // at destruction, flushes fatal message to g2LogWorker
    FatalTrigger::~FatalTrigger() {
      // either we will stay here eternally, or it's in unit-test mode
      gFatalToMLogWorkerFunctionPtr(mMessage);
    }
    
    // This mimics the original "std::put_time(const std::tm* tmb, const charT* fmt)"
    // This is needed since latest version (at time of writing) of gcc4.7 does not implement this library function yet.
    // return value is SIMPLIFIED to only return a std::string
    std::string put_time(const struct tm* tmb, const char* c_time_format)
    {
      const size_t size = 1024;
      char buffer[size]; // IMPORTANT: check now and then for when gcc will implement std::put_time finns.
      //                    ... also ... This is way more buffer space then we need
      auto success = std::strftime(buffer, size, c_time_format, tmb);
      if (0 == success)
        return c_time_format; // For this hack it is OK but in case of more permanent we really should throw here, or even assert
      return buffer;
    }

  } // ns internal
  
  
  tm localtime(const std::time_t& time)
  {
    struct tm tm_snapshot;
    localtime_r(&time, &tm_snapshot); // POSIX
    return tm_snapshot;
  }
  
  /// returns a std::string with content of time_t as localtime formatted by input format string
  /// * format string must conform to std::put_time
  /// This is similar to std::put_time(std::localtime(std::time_t*), time_format.c_str());
  std::string localtime_formatted(const std::time_t& time_snapshot, const std::string& time_format)
  {
    std::tm t = localtime(time_snapshot); // could be const, but cannot due to VS2012 is non conformant for C++11's std::put_time (see above)
    std::stringstream buffer;
    buffer << MeehLog::internal::put_time(&t, time_format.c_str());  // format example: //"%Y/%m/%d %H:%M:%S");
    return buffer.str();
  }

} // ns MeehLog

