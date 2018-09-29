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
  
  let statusBarController = StatusBarController()
  let sharedRouterMgmr = RouterManager.shared()
  static let javaDetector = DetectJava()
  
  override init() {
    super.init()
    if (!SwiftMainDelegate.javaDetector.isJavaFound()) {
    SwiftMainDelegate.javaDetector.findIt()
      if (!SwiftMainDelegate.javaDetector.isJavaFound()) {
        print("Could not find java....")
        terminate("No java..")
      }
    }
    let javaBinPath = SwiftMainDelegate.javaDetector.javaHome
    RouterProcessStatus.knownJavaBinPath = javaBinPath
    print("Found java home = ", javaBinPath)
    
    let (portIsNotTaken, _) = RouterProcessStatus.checkTcpPortForListen(port: 7657)
    if (!portIsNotTaken) {
      RouterProcessStatus.isRouterRunning = true
      RouterProcessStatus.isRouterChildProcess = false
      print("I2P Router seems to be running")
    } else {
      RouterProcessStatus.isRouterRunning = false
      print("I2P Router seems to NOT be running")
    }
  } // End of init()
  
  @objc func findInstalledI2PVersion() {
    var i2pPath = NSHomeDirectory()
    i2pPath += "/Library/I2P"
    var jExecPath:String = RouterProcessStatus.knownJavaBinPath!
    
    // Sometimes, home will return the binary, sometimes the actual home dir. This fixes the diverge.
    // If JRE is detected, binary follows - if it's JDK, home follows.
    if (jExecPath.hasSuffix("Home")) {
      jExecPath += "/jre/bin/java"
    }
    
    let jarPath = i2pPath + "/lib/i2p.jar"
    
    let subCmd = jExecPath + " -cp " + jarPath + " net.i2p.CoreVersion"
    
    let cmdArgs:[String] = ["-c", subCmd]
    print(cmdArgs)
    let sub:Subprocess = Subprocess.init(executablePath: "/bin/sh", arguments: cmdArgs)
    let results:ExecutionResult = sub.execute(captureOutput: true)!
    if (results.didCaptureOutput) {
      if (results.status == 0) {
        let i2pVersion = results.outputLines.first?.replace(target: "I2P Core version: ", withString: "")
        NSLog("I2P version detected: %@",i2pVersion ?? "Unknown")
        RouterProcessStatus.routerVersion = i2pVersion
        RouterManager.shared().eventManager.trigger(eventName: "router_version", information: i2pVersion)
        RouterManager.shared().eventManager.trigger(eventName: "router_can_start", information: i2pVersion)
      } else {
        NSLog("Non zero exit code from subprocess while trying to detect version number!")
        for line in results.errorsLines {
          NSLog(line)
        }
      }
    } else {
      print("Warning: Version Detection did NOT captured output")
    }
  }
  
  @objc func applicationDidFinishLaunching() {
    var i2pPath = NSHomeDirectory()
    i2pPath += "/Library/I2P"
    
  }
  
  @objc func listenForEvent(eventName: String, callbackActionFn: @escaping ((Any?)->()) ) {
    RouterManager.shared().eventManager.listenTo(eventName: eventName, action: callbackActionFn )
  }
  
  @objc func triggerEvent(en: String, details: String? = nil) {
    RouterManager.shared().eventManager.trigger(eventName: en, information: details)
  }
  
  @objc static func openLink(url: String) {
    SBridge.sharedInstance().openUrl(url)
  }
  
  @objc func applicationWillTerminate() {
    // Shutdown stuff
  }
  
  @objc func terminate(_ why: Any?) {
    print("Stopping cause of ", why!)
  }
}

