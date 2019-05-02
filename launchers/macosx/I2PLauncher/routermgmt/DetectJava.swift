//
//  DetectJava.swift
//  JavaI2PWrapper
//
//  Created by Mikal Villa on 24/03/2018.
//  Copyright © 2018 I2P. All rights reserved.
//

import Foundation


@objc class DetectJava : NSObject {
  
  static var hasJRE : Bool = false
  static var hasJDK : Bool = false
  static var userWantJRE : Bool = false
  static var userAcceptOracleEULA : Bool = false
  
  private static var sharedDetectJava: DetectJava = {
    let javaDetector = DetectJava()
    
    // Configuration
    // ...
    return javaDetector
  }()
  
  // Initialization
  
  private override init() {
    super.init()
  }
  
  // MARK: - Accessors
  
  
  class func shared() -> DetectJava {
    return sharedDetectJava
  }
  
  @objc var javaBinary: String? {
    didSet{
      print("DetectJava.javaBinary was set to "+self.javaBinary!)
    }
  }
  
  // Java checks
  @objc var javaHome: String = ""{
    
    //Called before the change
    willSet(newValue){
      print("DetectJava.javaHome will change from "+self.javaHome+" to "+newValue)
    }
    
    //Called after the change
    didSet{
      DetectJava.hasJRE = true
      // javaHome will have a trailing \n which we remove to not break the cli
      self.javaBinary = (self.javaHome+"/bin/java").replace(target: "\n", withString: "")
      print("DetectJava.javaHome did change to "+self.javaHome)
      //RouterManager.shared().eventManager.trigger(eventName: "java_found", information: self.javaHome)
    }
  };
  private var testedEnv : Bool = false
  private var testedJH : Bool = false
  private var testedDfl : Bool = false
  
  @objc func isJavaFound() -> Bool {
    if !(self.javaHome.isEmpty) {
      return true
    }
    return false
  }
  
  /**
   *
   * The order of the code blocks will decide the order, which will define the preffered.
   *
   **/
  @objc func findIt() {
    if (DetectJava.hasJRE) {
      return
    }
    print("Start with checking environment variable")
    self.checkJavaEnvironmentVariable()
    if !(self.javaHome.isEmpty) {
      DetectJava.hasJRE = true
      return
    }
    print("Checking with the java_home util")
    self.runJavaHomeCmd()
    if !(self.javaHome.isEmpty) {
      DetectJava.hasJRE = true
      return
    }
    print("Checking default JRE install path")
    self.checkDefaultJREPath()
    if !(self.javaHome.isEmpty) {
      DetectJava.hasJRE = true
      return
    }
  }
  
  @objc func getJavaViaLibexecBin() -> Array<String> {
    return ["/usr/libexec/java_home", "-v", "1.7+", "--exec", "java"]
  }
  
  @objc func runJavaHomeCmd() {
    let task = Process()
    task.launchPath = "/usr/libexec/java_home"
    task.arguments = ["-v", "1.7+"]
    let pipe = Pipe()
    task.standardOutput = pipe
    let outHandle = pipe.fileHandleForReading
    outHandle.waitForDataInBackgroundAndNotify()
    
    var obs1 : NSObjectProtocol!
    obs1 = NotificationCenter.default.addObserver(
      forName: NSNotification.Name.NSFileHandleDataAvailable,
      object: outHandle, queue: nil) {
        notification -> Void in
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
    obs2 = NotificationCenter.default.addObserver(
      forName: Process.didTerminateNotification,
      object: task, queue: nil) {
        notification -> Void in
          print("terminated")
          NotificationCenter.default.removeObserver(obs2)
    }
    
    task.launch()
    task.waitUntilExit()
    self.testedJH = true
  }
  
  
  @objc func checkDefaultJREPath() {
    var isDir : ObjCBool = false;
    let defaultJREPath = "/Library/Internet Plug-Ins/JavaAppletPlugin.plugin/Contents/Home"
    if (FileManager.default.fileExists(atPath: defaultJREPath, isDirectory:&isDir) && isDir.boolValue) {
      // Found it!!
      self.javaHome = defaultJREPath
    }
    self.testedDfl = true
    // No JRE here
  }
  
  @objc func getEnvironmentVar(_ name: String) -> String? {
    guard let rawValue = getenv(name) else { return nil }
    return String(utf8String: rawValue)
  }
  
  @objc func checkJavaEnvironmentVariable() {
    let dic = ProcessInfo.processInfo.environment
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
