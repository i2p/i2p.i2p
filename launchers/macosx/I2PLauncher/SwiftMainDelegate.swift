//
//  SwiftMainDelegate.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation
import Cocoa

@objc class SwiftMainDelegate : NSObject {
  
  //let statusItem = NSStatusBar.system().statusItem(withLength: NSSquareStatusItemLength )
  let statusBarController = StatusBarController()
  let javaDetector = DetectJava()
  static let objCBridge = SBridge()
  
  override init() {
    super.init()
    
    self.javaDetector.findIt()
    if (!javaDetector.isJavaFound()) {
      print("Could not find java....")
      terminate("No java..")
    }
    let javaBinPath = self.javaDetector.javaHome
    print("Found java home = ", javaBinPath)
    
    let (portIsNotTaken, descPort) = RouterProcessStatus.checkTcpPortForListen(port: 7657)
    if (!portIsNotTaken) {
      RouterProcessStatus.isRouterRunning = true
      RouterProcessStatus.isRouterChildProcess = false
      print("I2P Router seems to be running")
    } else {
      RouterProcessStatus.isRouterRunning = false
      print("I2P Router seems to NOT be running")
      
    }
    
    
  }
  
  func findInstalledI2PVersion(jarPath: String, javaBin: String) {
    var i2pPath = NSHomeDirectory()
    i2pPath += "/Library/I2P"
    var jExecPath:String = javaBin
    
    // Sometimes, home will return the binary, sometimes the actual home dir. This fixes the diverge.
    // If JRE is detected, binary follows - if it's JDK, home follows.
    if (jExecPath.hasSuffix("Home")) {
      jExecPath += "/jre/bin/java"
    }
    
    let subCmd = jExecPath + " -cp " + jarPath + " net.i2p.CoreVersion"
    
    var cmdArgs:[String] = ["-c", subCmd]
    print(cmdArgs)
    let sub:Subprocess = Subprocess.init(executablePath: "/bin/sh", arguments: cmdArgs)
    let results:ExecutionResult = sub.execute(captureOutput: true)!
    if (results.didCaptureOutput) {
      print("captured output")
      let i2pVersion = results.outputLines.first?.replace(target: "I2P Core version: ", withString: "")
      print("I2P version detected: ",i2pVersion!)
      RouterProcessStatus.routerVersion = i2pVersion
    } else {
      print("did NOT captured output")
      
    }
  }
  
  @objc func applicationDidFinishLaunching() {
    print("Hello from swift!")
    var i2pPath = NSHomeDirectory()
    i2pPath += "/Library/I2P"
    
    let javaBinPath = self.javaDetector.javaHome.replace(target: " ", withString: "\\ ")
    
    let fileManager = FileManager()
    var ok = ObjCBool(true)
    let doesI2PDirExists = fileManager.fileExists(atPath: i2pPath, isDirectory: &ok)
    
    if (!doesI2PDirExists) {
      // Deploy
    }
    
    let i2pJarPath = i2pPath + "/lib/i2p.jar"
    
    findInstalledI2PVersion(jarPath: i2pJarPath, javaBin: javaBinPath)
  }
  
  @objc static func openLink(url: String) {
    objCBridge.openUrl(url)
  }
  
  @objc func applicationWillTerminate() {
    // Shutdown stuff
  }
  
  @objc func terminate(_ why: Any?) {
    print("Stopping cause of ", why!)
  }
}

