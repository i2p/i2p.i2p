//
//  DetectJava.swift
//  JavaI2PWrapper
//
//  Created by Mikal Villa on 24/03/2018.
//  Copyright © 2018 I2P. All rights reserved.
//

import Foundation

class DetectJava : NSObject {
  
  var hasJRE : Bool = false
  var userWantJRE : Bool = false
  var userAcceptOracleEULA : Bool = false
  
  // Java checks
  var javaHome: String = ""{
    
    //Called before the change
    willSet(newValue){
      print("DetectJava.javaHome will change from "+self.javaHome+" to "+newValue)
    }
    
    //Called after the change
    didSet{
      hasJRE = true
      print("MDetectJava.javaHome did change from "+oldValue+" to "+self.javaHome)
    }
  };
  private var testedEnv : Bool = false
  private var testedJH : Bool = false
  private var testedDfl : Bool = false
  
  func isJavaFound() -> Bool {
    if !(self.javaHome.isEmpty) {
      return true
    }
    return false
  }
  
  func findIt() {
    print("Start with checking environment variable")
    self.checkJavaEnvironmentVariable()
    if !(self.javaHome.isEmpty) {
      RouterProcessStatus.knownJavaBinPath = Optional.some(self.javaHome)
      hasJRE = true
      return
    }
    print("Checking default JRE install path")
    self.checkDefaultJREPath()
    if !(self.javaHome.isEmpty) {
      RouterProcessStatus.knownJavaBinPath = Optional.some(self.javaHome)
      hasJRE = true
      return
    }
    print("Checking with the java_home util")
    self.runJavaHomeCmd()
    if !(self.javaHome.isEmpty) {
      RouterProcessStatus.knownJavaBinPath = Optional.some(self.javaHome)
      hasJRE = true
      return
    }
  }
  
  func runJavaHomeCmd() {
    let task = Process()
    task.launchPath = "/usr/libexec/java_home"
    task.arguments = []
    let pipe = Pipe()
    task.standardOutput = pipe
    let outHandle = pipe.fileHandleForReading
    outHandle.waitForDataInBackgroundAndNotify()
    
    var obs1 : NSObjectProtocol!
    obs1 = NotificationCenter.default.addObserver(forName: NSNotification.Name.NSFileHandleDataAvailable,
                                                  object: outHandle, queue: nil) {  notification -> Void in
                                                    let data = outHandle.availableData
                                                    if data.count > 0 {
                                                      let str = NSString(data: data, encoding: String.Encoding.utf8.rawValue)
                                                      if (str != nil) {
                                                        let stringVal = str! as String
                                                        print("got output: "+stringVal)
                                                        self.javaHome = stringVal
                                                      }
                                                      // TODO: Found something, check it out
                                                      outHandle.waitForDataInBackgroundAndNotify()
                                                    } else {
                                                      print("EOF on stdout from process")
                                                      NotificationCenter.default.removeObserver(obs1)
                                                      // No JRE so far
                                                    }
    }
    
    var obs2 : NSObjectProtocol!
    obs2 = NotificationCenter.default.addObserver(forName: Process.didTerminateNotification,
                                                  object: task, queue: nil) { notification -> Void in
                                                    print("terminated")
                                                    NotificationCenter.default.removeObserver(obs2)
    }
    
    task.launch()
    task.waitUntilExit()
    self.testedJH = true
  }
  
  func checkDefaultJREPath() {
    let defaultJREPath = "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home/bin/java"
    if FileManager.default.fileExists(atPath: defaultJREPath) {
      // Found it!!
      self.javaHome = defaultJREPath
    }
    self.testedDfl = true
    // No JRE here
  }
  
  func getEnvironmentVar(_ name: String) -> String? {
    guard let rawValue = getenv(name) else { return nil }
    return String(utf8String: rawValue)
  }
  
  func checkJavaEnvironmentVariable() {
    let dic = ProcessInfo.processInfo.environment
    //ProcessInfo.processInfo.environment["JAVA_HOME"]
    if let javaHomeEnv = dic["JAVA_HOME"] {
      // Maybe we got an JRE
      print("Found JAVA_HOME with value:")
      print(javaHomeEnv)
      self.javaHome = javaHomeEnv
    }
    self.testedEnv = true
    print("JAVA HOME is not set")
  }
  
  
}
