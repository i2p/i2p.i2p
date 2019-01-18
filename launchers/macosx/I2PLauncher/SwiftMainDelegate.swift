//
//  SwiftMainDelegate.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation
import Cocoa

extension Notification.Name {
  static let killLauncher = Notification.Name("killStartupLauncher")
}

class Logger {
  static func MLog<T>(level:Int32, _ object: T?,file:String = #file, function:String = #function, line:Int = #line) {
    SBridge.logProxy(level, formattedMsg: "\(makeTag(function: function, file: file, line: line)) : \(object)")
  }
  
  private static func makeTag(function: String, file: String, line: Int) -> String{
    let url = NSURL(fileURLWithPath: file)
    let className:String! = url.lastPathComponent == nil ? file: url.lastPathComponent!
    return "\(className) \(function)[\(line)]"
  }
  
}


@objc class SwiftMainDelegate : NSObject {
  
  let statusBarController = StatusBarController()
  let sharedRouterMgmr = RouterManager.shared()
  
  override init() {
    super.init()
    if (!DetectJava.shared().isJavaFound()) {
    DetectJava.shared().findIt()
      if (!DetectJava.shared().isJavaFound()) {
        Logger.MLog(level:3, "Could not find java....")
        terminate("No java..")
      }
    }
    let javaBinPath = DetectJava.shared().javaBinary
    Logger.MLog(level:1, "".appendingFormat("Found java home = %@", javaBinPath!))
    
    let (portIsNotTaken, _) = RouterProcessStatus.checkTcpPortForListen(port: 7657)
    if (!portIsNotTaken) {
      RouterProcessStatus.isRouterRunning = true
      RouterProcessStatus.isRouterChildProcess = false
      Logger.MLog(level:2, "I2P Router seems to be running")
    } else {
      RouterProcessStatus.isRouterRunning = false
      Logger.MLog(level:2, "I2P Router seems to NOT be running")
    }
  } // End of init()
  
  @objc func findInstalledI2PVersion() {
    var i2pPath = Preferences.shared().i2pBaseDirectory
    let jExecPath:String = Preferences.shared().javaCommandPath
    
    let jarPath = i2pPath + "/lib/i2p.jar"
    
    let subCmd = jExecPath + "-cp " + jarPath + " net.i2p.CoreVersion"
    
    let cmdArgs:[String] = ["-c", subCmd]
    print(cmdArgs)
    let sub:Subprocess = Subprocess.init(executablePath: "/bin/sh", arguments: cmdArgs)
    let results:ExecutionResult = sub.execute(captureOutput: true)!
    if (results.didCaptureOutput) {
      if (results.status == 0) {
        let i2pVersion = results.outputLines.first?.replace(target: "I2P Core version: ", withString: "")
        Logger.MLog(level: 1, "".appendingFormat("I2P version detected: %@",i2pVersion ?? "Unknown"))
        RouterProcessStatus.routerVersion = i2pVersion
        RouterManager.shared().eventManager.trigger(eventName: "router_version", information: i2pVersion)
      } else {
        Logger.MLog(level: 2, "Non zero exit code from subprocess while trying to detect version number!")
        for line in results.errorsLines {
          Logger.MLog(level: 2, line)
        }
      }
    } else {
      Logger.MLog(level: 1, "Warning: Version Detection did NOT captured output")
    }
  }
  
  func triggerDockIconShowHide(showIcon state: Bool) -> Bool {
    var result: Bool
    if state {
      result = NSApp.setActivationPolicy(NSApplicationActivationPolicy.regular)
    } else {
      result = NSApp.setActivationPolicy(NSApplicationActivationPolicy.accessory)
    }
    return result
  }
  
  func getDockIconStateIsShowing() -> Bool {
    if NSApp.activationPolicy() == NSApplicationActivationPolicy.regular {
      return true
    } else {
      return false
    }
  }
  
  @objc func applicationDidFinishLaunching() {
    switch Preferences.shared().showAsIconMode {
    case .bothIcon, .dockIcon:
      if (!getDockIconStateIsShowing()) {
        triggerDockIconShowHide(showIcon: true)
      }
    default:
      if (getDockIconStateIsShowing()) {
        triggerDockIconShowHide(showIcon: false)
      }
    }
    let launcherAppId = "net.i2p.bootstrap.macosx.StartupItemApp"
    let runningApps = NSWorkspace.shared().runningApplications
    let isRunning = !runningApps.filter { $0.bundleIdentifier == launcherAppId }.isEmpty
    // SMLoginItemSetEnabled(launcherAppId as CFString, true)
    
    if isRunning {
      DistributedNotificationCenter.default().post(name: .killLauncher, object: Bundle.main.bundleIdentifier!)
    }
  }
  
  @objc func listenForEvent(eventName: String, callbackActionFn: @escaping ((Any?)->()) ) {
    RouterManager.shared().eventManager.listenTo(eventName: eventName, action: callbackActionFn )
  }
  
  @objc func triggerEvent(en: String, details: String? = nil) {
    RouterManager.shared().eventManager.trigger(eventName: en, information: details)
  }
  
  @objc static func openLink(url: String) {
    NSLog("Trying to open \(url)")
    NSWorkspace.shared().open(NSURL(string: url)! as URL)
  }
  
  @objc func applicationWillTerminate() {
    // Shutdown stuff
    if (Preferences.shared().stopRouterOnLauncherShutdown) {
      RouterManager.shared().routerRunner.TeardownLaunchd()
      sleep(2)
      let status: AgentStatus? = RouterRunner.launchAgent?.status()
      if status != .unloaded {
        Logger.MLog(level:2, "Router service not yet stopped")
        RouterManager.shared().routerRunner.TeardownLaunchd()
        sleep(5)
      }
    }
  }
  
  @objc func terminate(_ why: Any?) {
    Logger.MLog(level:2, "".appendingFormat("Stopping cause of ", why! as! CVarArg))
  }
}

