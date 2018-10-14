//
//  logger_c.h
//  I2PLauncher
//
//  Created by Mikal Villa on 30/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

#ifndef logger_c_h
#define logger_c_h

#include "Logger.h"
#include "LoggerWorker.hpp"

/*
void genericLogger(int loglevel, va_list params) {
#ifdef __cplusplus
  const char * paramArray[10];
  int numParams = 0;
  
  NSString* arg = nil;
  while ((arg = va_arg(params, NSString *))) {
    paramArray[numParams++] = [arg cStringUsingEncoding:[NSString defaultCStringEncoding]];
  }
  
  switch (loglevel) {
    case 0:
      MLOGF(ANNOYING) << params;
      break;
    case 1:
      MLOGF(DEBUG) << params;
      break;
    case 2:
      MLOGF(INFO) << params;
      break;
    case 3:
      MLOGF(WARN) << params;
      break;
    default:
      assert(false);
  }
#endif
}
 */

inline void MLog(int loglevel, NSString* format, ...)
{
#ifdef __cplusplus
  
  va_list vargs;
  va_start(vargs, format);
  NSString* formattedMessage = [[NSString alloc] initWithFormat:format arguments:vargs];
  va_end(vargs);
  
  NSString* message = formattedMessage;
  
  switch (loglevel) {
    case 0:
      MLOG(ANNOYING) << [message UTF8String];
      break;
    case 1:
      MLOG(DEBUG) << [message UTF8String];
      break;
    case 2:
      MLOG(INFO) << [message UTF8String];
      break;
    case 3:
      MLOG(WARN) << [message UTF8String];
      break;
    case 4:
      MLOG(ERROR) << [message UTF8String];
      break;
    default:
#if DEBUG
      assert(false);
#else
      return;
#endif
  }
  
#endif
}



#define MMLog(format_string,...) ((MLog(1, [NSString stringWithFormat:format_string,##__VA_ARGS__])))

#endif /* logger_c_h */
