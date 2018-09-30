#pragma once

#include <CoreFoundation/CoreFoundation.h>
#include <unistd.h>
#include <sys/event.h>


using callbackType = void (CFFileDescriptorRef, CFOptionFlags, void *);
using HandleFunction = std::function<void(int)>;

static inline void noteProcDeath(CFFileDescriptorRef fdref, CFOptionFlags callBackTypes, void *info) {
    struct kevent kev;
    int fd = CFFileDescriptorGetNativeDescriptor(fdref);
    kevent(fd, NULL, 0, &kev, 1, NULL);
    // take action on death of process here
    NSLog(@"process with pid '%u' died\n", (unsigned int)kev.ident);
    //sendUserNotification(APP_IDSTR, @"The I2P router has stopped.");
    CFFileDescriptorInvalidate(fdref);
    CFRelease(fdref); // the CFFileDescriptorRef is no longer of any use in this example
}
// one argument, an integer pid to watch, required
int watchPid(int pid, callbackType callback = noteProcDeath) {
    int fd = kqueue();
    struct kevent kev;
    EV_SET(&kev, pid, EVFILT_PROC, EV_ADD|EV_ENABLE, NOTE_EXIT, 0, NULL);
    kevent(fd, &kev, 1, NULL, 0, NULL);
    CFFileDescriptorRef fdref = CFFileDescriptorCreate(kCFAllocatorDefault, fd, true, callback, NULL);
    CFFileDescriptorEnableCallBacks(fdref, kCFFileDescriptorReadCallBack);
    CFRunLoopSourceRef source = CFFileDescriptorCreateRunLoopSource(kCFAllocatorDefault, fdref, 0);
    CFRunLoopAddSource(CFRunLoopGetMain(), source, kCFRunLoopDefaultMode);
    CFRelease(source);
    /*
    seconds
    The length of time to run the run loop. If 0, only one pass is made through the run loop before returning;
    if multiple sources or timers are ready to fire immediately, only one (possibly two if one is a version
    0 source) will be fired, regardless of the value of returnAfterSourceHandled.
    */
    CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0, false);
    return 0;
}
