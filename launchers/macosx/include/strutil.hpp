#ifndef __STRUTIL_HPP__
#define __STRUTIL_HPP__

#include <algorithm>
#include <functional>
#include <string>
#include <memory>
#include <iostream>
#include <cctype>
#include <locale>
#include "optional.h"

#import <Foundation/NSString.h>

#include <CoreFoundation/CoreFoundation.h>
#include <CoreFoundation/CFArray.h>
#include <CoreFoundation/CFString.h>

inline std::string strprintf(const char *fromat, ...)
{
	std::string s;
	s.resize(128); // best guess
	char *buff = const_cast<char *>(s.data());

	va_list arglist;
	va_start(arglist, fromat);
	auto len = vsnprintf(buff, 128, fromat, arglist);
	va_end(arglist);

	if (len > 127)
	{
		va_start(arglist, fromat);
		s.resize(len + 1); // leave room for null terminator
		buff = const_cast<char *>(s.data());
		len = vsnprintf(buff, len+1, fromat, arglist);
		va_end(arglist);
	}
	s.resize(len);
	return s; // move semantics FTW
}

inline std::string extractString(CFStringRef value)
{
  const char * data = CFStringGetCStringPtr(value, kCFStringEncodingUTF8);
  if (data != NULL)
  {
    return std::string(data, strlen(data));
  } else {
    CFIndex strSize = CFStringGetLength(value)+1;
    char * retry = (char *)malloc((int)strSize);
    if (CFStringGetCString(value, retry, strSize, kCFStringEncodingUTF8)) {
      return std::string(retry, strlen(retry));
    }
    return std::string("[null]");
  }
}

inline bool replace(std::string& str, const std::string& from, const std::string& to) {
  size_t start_pos = str.find(from);
  if(start_pos == std::string::npos)
    return false;
  str.replace(start_pos, from.length(), to);
  return true;
}


// trim from start (in place)
static inline void ltrim(std::string &s) {
    s.erase(s.begin(), std::find_if(s.begin(), s.end(),
            std::not1(std::ptr_fun<int, int>(std::isspace))));
}

// trim from end (in place)
static inline void rtrim(std::string &s) {
    s.erase(std::find_if(s.rbegin(), s.rend(),
            std::not1(std::ptr_fun<int, int>(std::isspace))).base(), s.end());
}

// trim from both ends (in place)
static inline void trim(std::string &s) {
    ltrim(s);
    rtrim(s);
}

// trim from start (copying)
static inline std::string ltrim_copy(std::string s) {
    ltrim(s);
    return s;
}

// trim from end (copying)
static inline std::string rtrim_copy(std::string s) {
    rtrim(s);
    return s;
}

// trim from both ends (copying)
static inline std::string trim_copy(std::string s) {
    trim(s);
    return s;
}

inline NSString* stdStringToNSString(std::string &stdstr) {
  return [NSString stringWithUTF8String:stdstr.c_str()];
}

inline std::string nsStringToStd(NSString* nsStr) {
  const char *charlist = [nsStr UTF8String];
  return std::string(charlist);
}

#if __cplusplus > 201402L


using std::optional;

// Use CFStringRef instead of NSString*, otherwise disable ARC
inline optional<CFStringRef> optionalString(bool val) {
  optional<CFStringRef> myOptString;
  if(val) {
    // Cast to corresponding CoreFoundation object
    myOptString = (CFStringRef)@"String";
  }
  return myOptString;
}

#endif


#endif
