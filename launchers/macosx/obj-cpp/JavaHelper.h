#pragma once

#include <functional>
#include <memory>
#include <iostream>
#include <algorithm>
#include <cstring>
#include <sstream>
#include <list>
#include <stdlib.h>

#include <Foundation/Foundation.h>
#include <CoreFoundation/CoreFoundation.h>
#include <CoreFoundation/CFStream.h>
#include <CoreFoundation/CFPropertyList.h>
#include <CoreFoundation/CFDictionary.h>
#include <CoreFoundation/CFArray.h>
#include <CoreFoundation/CFString.h>

#include "optional.hpp"
#include "strutil.hpp"
#include "subprocess.hpp"
#include "neither/maybe.hpp"
#include "RouterTask.h"

using namespace subprocess;
using namespace neither;

using maybeAnRouterRunner = std::experimental::optional<RouterTask*>;

extern std::mutex globalRouterStatusMutex;
extern maybeAnRouterRunner globalRouterStatus;


#define DEF_MIN_JVM_VER "1.7+"

class JvmVersion
{
public:
  std::string JVMName;
  std::string JVMHomePath;
  std::string JVMPlatformVersion;

  inline const char * ToCString()
  {
    std::stringstream ss;
    ss << "JvmVersion(name=" << JVMName.c_str() << ",version=";
    ss << JVMPlatformVersion.c_str() << ",home=" << JVMHomePath.c_str() << ")";
    std::string s = ss.str();
    return s.c_str();
  }

  inline bool HasContent()
  {
    return (
      std::strlen(JVMName.c_str()) > 0 &&
      std::strlen(JVMHomePath.c_str()) > 0 &&
      std::strlen(JVMPlatformVersion.c_str()) > 0
    );
  }
};

typedef std::shared_ptr<JvmVersion> JvmVersionPtr;
typedef std::shared_ptr<std::list<JvmVersionPtr> > JvmListPtr;
typedef std::shared_ptr<std::list<std::shared_ptr<JvmVersion> > > JvmListSharedPtr;
typedef void(^MenuBarControllerActionBlock)(BOOL active);

extern JvmListSharedPtr gRawJvmList;

class JvmHomeContext : public std::enable_shared_from_this<JvmHomeContext>
{
public:

  inline void setJvm(JvmVersionPtr* current)
  {
    mCurrent = *current;
  }

  inline JvmListPtr getJvmList()
  {
    return gRawJvmList;
  }

  inline std::shared_ptr<JvmHomeContext> getContext()
  {
    return shared_from_this();
  }

  inline std::string getJavaHome()
  {
    if (mCurrent)
    {
      return mCurrent->JVMHomePath;
    }
    return gRawJvmList->back()->JVMHomePath;
  }
private:
  JvmVersionPtr mCurrent;
};

static void processJvmEntry (const void* key, const void* value, void* context) {
  //CFShow(key);
  //CFShow(value);

  // The reason for using strprintf is to "force" a copy of the values,
  // since local variables gets deleted once this function returns.
  auto currentJvm = reinterpret_cast<JvmVersion*>(context);
  if (CFEqual((CFStringRef)key,CFSTR("JVMName"))) {
    auto strVal = extractString((CFStringRef)value);
    currentJvm->JVMName = strprintf("%s",strVal.c_str());
  }
  if (CFEqual((CFStringRef)key,CFSTR("JVMHomePath"))) {
    auto strVal = extractString((CFStringRef)value);
    currentJvm->JVMHomePath = strprintf("%s",strVal.c_str());
  }
  if (CFEqual((CFStringRef)key,CFSTR("JVMPlatformVersion"))) {
    auto strVal = extractString((CFStringRef)value);
    currentJvm->JVMPlatformVersion = strprintf("%s",strVal.c_str());
  }
}

static void processJvmPlistEntries (const void* item, void* context) {
  CFDictionaryRef dict = CFDictionaryCreateCopy(kCFAllocatorDefault, (CFDictionaryRef)item);

  JvmVersionPtr currentJvmPtr = std::shared_ptr<JvmVersion>(new JvmVersion());
  struct CFunctional
  {
    static void applier(const void* key, const void* value, void* context){
      // The reason for using strprintf is to "force" a copy of the values,
      // since local variables gets deleted once this function returns.
      auto currentJvm = static_cast<JvmVersion*>(context);
      if (CFEqual((CFStringRef)key,CFSTR("JVMName"))) {
        auto d = extractString((CFStringRef)value);
        currentJvm->JVMName = trim_copy(d);
      }
      if (CFEqual((CFStringRef)key,CFSTR("JVMHomePath"))) {
        auto d = extractString((CFStringRef)value);
        currentJvm->JVMHomePath = trim_copy(d);
      }
      if (CFEqual((CFStringRef)key,CFSTR("JVMPlatformVersion"))) {
        auto d = extractString((CFStringRef)value);
        currentJvm->JVMPlatformVersion = trim_copy(d);
      }

    }
  };

  CFDictionaryApplyFunction(dict, CFunctional::applier, (void*)currentJvmPtr.get());

  if (currentJvmPtr->HasContent())
  {
    printf("Found JVM: %s\n\n", currentJvmPtr->ToCString());
    gRawJvmList->push_back(currentJvmPtr);
  }
}

static void listAllJavaInstallsAvailable()
{
  auto javaHomeRes = check_output({"/usr/libexec/java_home","-v",DEF_MIN_JVM_VER,"-X"});
  CFDataRef javaHomes = CFDataCreate(NULL, (const UInt8 *)javaHomeRes.buf.data(), strlen(javaHomeRes.buf.data()));

  //CFErrorRef err;
  CFPropertyListRef propertyList = CFPropertyListCreateWithData(kCFAllocatorDefault, javaHomes, kCFPropertyListImmutable, NULL, NULL);
  /*if (err)
  {
    NSError *error = (__bridge NSError *)err;
    NSLog(@"Failed to read property list: %@", error);
    [NSApp presentError: error];
    return nullptr;
  }*/


  //auto typeId = CFCopyTypeIDDescription(CFGetTypeID(propertyList));
  //auto test = CFCopyDescription(propertyList);
  //std::cout << "test: " << [test UTF8String] << " Type: " << [typeId UTF8String] << " num: " << jCount << std::endl;

  // Count number of entries in the property array list.
  // This is used to set max CRange for CFArrayApplyFunction.
  auto jCount = CFArrayGetCount((CFArrayRef)propertyList);

  CFArrayApplyFunction((CFArrayRef)propertyList, CFRangeMake(0, jCount), processJvmPlistEntries, NULL);
  //CFShow(propertyList);
}
