//
//  Logger.h
//  I2PLauncher
//
//  Created by Mikal Villa on 27/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//  Imported/Refactored from earlier C++ project of Meeh
//

#ifndef Logger_h
#define Logger_h

#ifdef __cplusplus

#include <string>
#include <sstream>
#include <iostream>
#include <cstdarg>
#include <chrono>
#include <functional>
#include <ctime>


/**
 * For details please see this
 * REFERENCE: http://www.cppreference.com/wiki/io/c/printf_format
 * \verbatim
 *
 There are different %-codes for different variable types, as well as options to
 limit the length of the variables and whatnot.
 Code Format
 %[flags][width][.precision][length]specifier
 SPECIFIERS
 ----------
 %c character
 %d signed integers
 %i signed integers
 %e scientific notation, with a lowercase “e”
 %E scientific notation, with a uppercase “E”
 %f floating point
 %g use %e or %f, whichever is shorter
 %G use %E or %f, whichever is shorter
 %o octal
 %s a string of characters
 %u unsigned integer
 %x unsigned hexadecimal, with lowercase letters
 %X unsigned hexadecimal, with uppercase letters
 %p a pointer
 %n the argument shall be a pointer to an integer into which is placed the number of characters written so far

 */

/*
 Usage:
 
 SharedLogWorker logger(argv[0], path_to_log_file);
 MeehLog::initializeLogging(&logger);
 
 MLOG(INFO) << "Test SLOG INFO";
 MLOG(DEBUG) << "Test SLOG DEBUG";
 
 */


class SharedLogWorker;

#if !(defined(__PRETTY_FUNCTION__))
#define __PRETTY_FUNCTION__   __FUNCTION__
#endif

const int ML_ANNOYING = 0;
const int ML_DEBUG = 1;
const int ML_INFO = 2;
const int ML_WARN = 3;
const int ML_ERROR = 4;
const int ML_FATAL = 5;
static const std::string kFatalLogExpression = "";

#define MLOG_ANNOYING  MeehLog::internal::LogMessage(__FILE__,__LINE__,__PRETTY_FUNCTION__,"ML_ANNOYING")
#define MLOG_DEBUG     MeehLog::internal::LogMessage(__FILE__,__LINE__,__PRETTY_FUNCTION__,"ML_DEBUG")
#define MLOG_INFO      MeehLog::internal::LogMessage(__FILE__,__LINE__,__PRETTY_FUNCTION__,"ML_INFO")
#define MLOG_WARN      MeehLog::internal::LogMessage(__FILE__,__LINE__,__PRETTY_FUNCTION__,"ML_WARN")
#define MLOG_ERROR     MeehLog::internal::LogMessage(__FILE__,__LINE__,__PRETTY_FUNCTION__,"ML_ERROR")
#define MLOG_FATAL     MeehLog::internal::LogContractMessage(__FILE__,__LINE__,__PRETTY_FUNCTION__,k_fatal_log_expression)

// MLOG(level) is the API for the stream log
#define MLOG(level) MLOG_##level.messageStream()

// conditional stream log
#define LOG_IF(level, boolean_expression)  \
  if(true == boolean_expression)           \
    MLOG_##level.messageStream()

#define MASSERT(boolean_expression)                                                    \
  if (false == (boolean_expression))                                                   \
    MeehLog::internal::LogContractMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__, #boolean_expression).messageStream()


#define MLOGF_ANNOYING  MeehLog::internal::LogMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,"ML_ANNOYING")
#define MLOGF_INFO      MeehLog::internal::LogMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,"ML_INFO")
#define MLOGF_DEBUG     MeehLog::internal::LogMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,"ML_DEBUG")
#define MLOGF_WARN      MeehLog::internal::LogMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,"ML_WARN")
#define MLOGF_ERROR     MeehLog::internal::LogMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,"ML_ERROR")
#define MLOGF_FATAL     MeehLog::internal::LogContractMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,k_fatal_log_expression)

// MLOGF(level,msg,...) is the API for the "printf" like log
#define MLOGF(level, printf_like_message, ...)                   \
  MLOGF_##level.messageSave(printf_like_message, ##__VA_ARGS__)

// conditional log printf syntax
#define MLOGF_IF(level,boolean_expression, printf_like_message, ...) \
  if(true == boolean_expression)                                    \
    MLOG_##level.messageSave(printf_like_message, ##__VA_ARGS__)

// Design By Contract, printf-like API syntax with variadic input parameters. Throws std::runtime_eror if contract breaks */
#define MASSERTF(boolean_expression, printf_like_message, ...)                                     \
  if (false == (boolean_expression))                                                               \
    MeehLog::internal::LogContractMessage(__FILE__, __LINE__, __PRETTY_FUNCTION__,#boolean_expression).messageSave(printf_like_message, ##__VA_ARGS__)

namespace MeehLog {
  
  
  // PUBLIC API:
  /** Install signal handler that catches FATAL C-runtime or OS signals
   SIGABRT  ABORT (ANSI), abnormal termination
   SIGFPE   Floating point exception (ANSI): http://en.wikipedia.org/wiki/SIGFPE
   SIGILL   ILlegal instruction (ANSI)
   SIGSEGV  Segmentation violation i.e. illegal memory reference
   SIGTERM  TERMINATION (ANSI) */
  void installSignalHandler();
  
  namespace internal {
    
    
    /** \return signal_name. Ref: signum.h and \ref installSignalHandler */
    std::string signalName(int signal_number);
    
    /** Re-"throw" a fatal signal, previously caught. This will exit the application
     * This is an internal only function. Do not use it elsewhere. It is triggered
     * from g2log, g2LogWorker after flushing messages to file */
    void exitWithDefaultSignalHandler(int signal_number);
    
    std::time_t systemtime_now();
    bool isLoggingInitialized();
    
    struct LogEntry {
      LogEntry(std::string msg, std::time_t timestamp) : mMsg(msg), mTimestamp(timestamp) {}
      LogEntry(const LogEntry& other): mMsg(other.mMsg), mTimestamp(other.mTimestamp) {}
      LogEntry& operator=(const LogEntry& other) {
        mMsg = other.mMsg;
        mTimestamp = other.mTimestamp;
        return *this;
      }
      
      
      std::string mMsg;
      std::time_t mTimestamp;
    };
    
    /** Trigger for flushing the message queue and exiting the application
     A thread that causes a FatalMessage will sleep forever until the
     application has exited (after message flush) */
    struct FatalMessage {
      enum FatalType {kReasonFatal, kReasonOS_FATAL_SIGNAL};
      FatalMessage(LogEntry message, FatalType type, int signal_id);
      ~FatalMessage() {};
      FatalMessage& operator=(const FatalMessage& fatal_message);
      
      
      LogEntry mMessage;
      FatalType mType;
      int mSignalId;
    };
    // Will trigger a FatalMessage sending
    struct FatalTrigger {
      FatalTrigger(const FatalMessage& message);
      ~FatalTrigger();
      FatalMessage mMessage;
    };

    // Log message for 'printf-like' or stream logging, it's a temporary message constructions
    class LogMessage {
    public:
      LogMessage(const std::string& file, const int line, const std::string& function, const std::string& level);
      virtual ~LogMessage(); // at destruction will flush the message
      
      std::ostringstream& messageStream() {return mStream;}
      
      // To generate warn on illegal printf format
    #ifndef __GNUC__
    #define  __attribute__(x)
    #endif
      // C++ get 'this' as first arg
      void messageSave(const char* printf_like_message, ...)
      __attribute__((format(printf, 2, 3) ));
      
    protected:
      const std::string mFile;
      const int mLine;
      const std::string mFunction;
      const std::string mLevel;
      std::ostringstream mStream;
      std::string mLogEntry;
      std::time_t mTimestamp;
    };

    // 'Design-by-Contract' temporary messsage construction
    class LogContractMessage : public LogMessage {
    public:
      LogContractMessage(const std::string& file, const int line,
                         const std::string& function, const std::string& boolean_expression);
      virtual ~LogContractMessage(); // at destruction will flush the message
      
    protected:
      const std::string mExpression;
    };
    
    //  wrap for std::chrono::system_clock::now()
    std::time_t systemtime_now();
    
  } // namespace internal
  
  
  
  /** Should be called at very first startup of the software with \ref SharedLogWorker pointer.
   * Ownership of the \ref SharedLogWorker is the responsibilkity of the caller */
  void initializeLogging(SharedLogWorker* logger);
  
  /** Shutdown the logging by making the pointer to the background logger to nullptr
   * The \ref pointer to the SharedLogWorker is owned by the instantniater \ref initializeLogging
   * and is not deleted.
   */
  void shutDownLogging();
  
  /** Same as the Shutdown above but called by the destructor of the LogWorker, thus ensuring that no further
   *  LOG(...) calls can happen to  a non-existing LogWorker.
   *  @param active MUST BE the LogWorker initialized for logging. If it is not then this call is just ignored
   *         and the logging continues to be active.
   * @return true if the correct worker was given,. and shutDownLogging was called
   */
  bool shutDownLoggingForActiveOnly(SharedLogWorker* active);
  
  
  typedef std::chrono::steady_clock::time_point steady_time_point;
  typedef std::chrono::time_point<std::chrono::system_clock>  system_time_point;
  typedef std::chrono::milliseconds milliseconds;
  typedef std::chrono::microseconds microseconds;
  
  /** return time representing POD struct (ref ctime + wchar) that is normally
   * retrieved with std::localtime. MeehLog::localtime is threadsafe which std::localtime is not.
   * MeehLog::localtime is probably used together with @ref MeehLog::systemtime_now */
  tm localtime(const std::time_t& time);
  
  /** format string must conform to std::put_time's demands.
   * WARNING: At time of writing there is only so-so compiler support for
   * std::put_time. A possible fix if your c++11 library is not updated is to
   * modify this to use std::strftime instead */
  std::string localtime_formatted(const std::time_t& time_snapshot, const std::string& time_format) ;
} // namespace MeehLog

#endif // __cplusplus

#endif /* Logger_h */
