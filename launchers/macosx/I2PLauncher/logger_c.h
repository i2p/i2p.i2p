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

void MLog(int loglevel, NSString* format, ...)
{
#ifdef __cplusplus
  static NSDateFormatter* timeStampFormat;
  if (!timeStampFormat) {
    timeStampFormat = [[NSDateFormatter alloc] init];
    [timeStampFormat setDateFormat:@"yyyy-MM-dd HH:mm:ss.SSS"];
    [timeStampFormat setTimeZone:[NSTimeZone systemTimeZone]];
  }
  
  NSString* timestamp = [timeStampFormat stringFromDate:[NSDate date]];
  
  va_list vargs;
  va_start(vargs, format);
  NSString* formattedMessage = [[NSString alloc] initWithFormat:format arguments:vargs];
  va_end(vargs);
  
  NSString* message = [NSString stringWithFormat:@"<%@> %@", timestamp, formattedMessage];
  
  switch (loglevel) {
    case 0:
      MLOGF(ANNOYING) << message;
      break;
    case 1:
      MLOGF(DEBUG) << message;
      break;
    case 2:
      MLOGF(INFO) << message;
      break;
    case 3:
      MLOGF(WARN) << message;
      break;
    case 4:
      MLOGF(ERROR) << message;
      break;
    default:
#if DEBUG
      assert(false);
#endif
  }
  
#endif
}


#endif /* logger_c_h */
