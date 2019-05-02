//
//  SwiftMainDelegate.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation
import Cocoa
import MBPopup

extension Notification.Name {
  static let killLauncher = Notification.Name("killStartupLauncher")
  static let upgradeRouter = Notification.Name("upgradeRouter")
  static let startRouter = Notification.Name("startRouter")
  static let stopRouter = Notification.Name("stopRouter")
}

class Logger {
  static func MLog<T>(level:Int32, _ object: T?,file:String = #file, function:String = #function, line:Int = #line) {
    SBridge.logProxy(level, formattedMsg: "\(makeTag(function: function, file: file, line: line)) : \(String(describing: object))")
  }
  
  private static func makeTag(function: String, file: String, line: Int) -> String{
    let url = NSURL(fileURLWithPath: file)
    let className:String! = url.lastPathComponent == nil ? file: url.lastPathComponent!
    return "\(String(describing: className)) \(function)[\(line)]"
  }
  
}


@objc class SwiftApplicationDelegate : NSObject, NSApplicationDelegate, NSMenuDelegate {
  
  let statusBarController = StatusBarController()
  let sharedRouterMgmr = RouterManager.shared()
  //let popupController: MBPopupController
  //let serviceTableViewController = ServiceTableViewController()
  //let editorTableViewController: EditorTableViewController
  
  // Constructor, think of it like an early entrypoint.
  override init() {
    //self.popupController = MBPopupController(contentView: serviceTableViewController.contentView)
    //self.editorTableViewController = serviceTableViewController.editorTableViewController
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
    
    let (portIsNotTaken, _) = NetworkUtil.checkTcpPortForListen(port: 7657)
    if (!portIsNotTaken) {
      RouterProcessStatus.isRouterRunning = true
      RouterProcessStatus.isRouterChildProcess = false
      Logger.MLog(level:2, "I2P Router seems to be running")
    } else {
      RouterProcessStatus.isRouterRunning = false
      Logger.MLog(level:2, "I2P Router seems to NOT be running")
    }
  } // End of init()
  
  // A function which detects the current installed I2P router version
  // NOTE: The return value tells if the function fails to detect I2P or not, and not if I2P is installed or not.
  @objc func findInstalledI2PVersion() -> Bool {
    let jarPath = Preferences.shared().i2pBaseDirectory + "/lib/i2p.jar"
    let subCmd = Preferences.shared().javaCommandPath + "-cp " + jarPath + " net.i2p.CoreVersion"
    let cmdArgs:[String] = ["-c", subCmd]
    
    let sub:Subprocess = Subprocess.init(executablePath: "/bin/sh", arguments: cmdArgs)
    let results:ExecutionResult = sub.execute(captureOutput: true)!
    
    if (results.didCaptureOutput) {
      if (results.status == 0) {
        let i2pVersion = results.outputLines.first?.replace(target: "I2P Core version: ", withString: "")
        Logger.MLog(level: 1, "".appendingFormat("I2P version detected: %@",i2pVersion ?? "Unknown"))
        RouterProcessStatus.routerVersion = i2pVersion
        RouterManager.shared().eventManager.trigger(eventName: "router_version", information: i2pVersion)
        return true
      } else {
        Logger.MLog(level: 2, "Non zero exit code from subprocess while trying to detect version number!")
        for line in results.errorsLines {
          Logger.MLog(level: 2, line)
        }
        return false
      }
    } else {
      Logger.MLog(level: 1, "Warning: Version Detection did NOT captured output")
    }
    return false
  }
  
  
  // Helper functions for the optional dock icon
  func triggerDockIconShowHide(showIcon state: Bool) -> Bool {
    var result: Bool
    if state {
      result = NSApp.setActivationPolicy(NSApplication.ActivationPolicy.regular)
    } else {
      result = NSApp.setActivationPolicy(NSApplication.ActivationPolicy.accessory)
    }
    return result
  }
  
  // Helper functions for the optional dock icon
  func getDockIconStateIsShowing() -> Bool {
    if NSApp.activationPolicy() == NSApplication.ActivationPolicy.regular {
      return true
    } else {
      return false
    }
  }
  
  @objc func updateServices() {
    /*
    serviceTableViewController.updateServices { [weak self] in
      let title = self?.serviceTableViewController.generalStatus == .crashed ? "-.-" : "I2PLauncher"
      self?.popupController.statusItem.title = title
      
      if Preferences.shared().notifyOnStatusChange {
        self?.serviceTableViewController.services.forEach { $0.notifyIfNecessary() }
      }
    }
    */
  }
  
  /**
   *
   * This is the swift "entrypoint". In C it would been "main(argc,argv)"
   *
   */
  @objc func applicationDidFinishLaunching() {
    switch Preferences.shared().showAsIconMode {
    case .bothIcon, .dockIcon:
      if (!getDockIconStateIsShowing()) {
        let _ = triggerDockIconShowHide(showIcon: true)
      }
    default:
      if (getDockIconStateIsShowing()) {
        let _ = triggerDockIconShowHide(showIcon: false)
      }
    }
    let runningApps = NSWorkspace.shared.runningApplications
    let isRunning = !runningApps.filter { $0.bundleIdentifier == Identifiers.launcherApplicationBundleId }.isEmpty
    // SMLoginItemSetEnabled(launcherAppId as CFString, true)
    
    if isRunning {
      DistributedNotificationCenter.default().post(name: .killLauncher, object: Bundle.main.bundleIdentifier!)
    }
    
    if (Preferences.shared().alsoStartFirefoxOnLaunch) {
      DispatchQueue.delay(.seconds(120)) {
        print("two minutes has passed, executing firefox manager")
        let _ = FirefoxManager.shared().executeFirefox()
      }
    }
    
    if #available(OSX 10.14, *) {
      Appearance.addObserver(self)
    } else {
      //popupController.backgroundView.backgroundColor = .white
    }
    
    NSUserNotificationCenter.default.delegate = self
    /*
    popupController.statusItem.button?.image = NSImage(named:"StatusBarButtonImage")
    popupController.statusItem.button?.toolTip = "I2P Launch Manager"
    popupController.statusItem.button?.font = NSFont(name: "SF Mono Regular", size: 10) ?? NSFont.systemFont(ofSize: 12)
    popupController.statusItem.length = 30
    
    popupController.contentView.wantsLayer = true
    popupController.contentView.layer?.masksToBounds = true
    
    serviceTableViewController.setup()
    
    popupController.willOpenPopup = { [weak self] _ in
      if self?.editorTableViewController.hidden == true {
        self?.serviceTableViewController.willOpenPopup()
      } else {
        self?.editorTableViewController.willOpenPopup()
      }
    }
    
    popupController.didOpenPopup = { [weak self] in
      if self?.editorTableViewController.hidden == false {
        self?.editorTableViewController.didOpenPopup()
      }
    }
    */
  }
  
  @objc func listenForEvent(eventName: String, callbackActionFn: @escaping ((Any?)->()) ) {
    RouterManager.shared().eventManager.listenTo(eventName: eventName, action: callbackActionFn )
  }
  
  @objc func triggerEvent(en: String, details: String? = nil) {
    RouterManager.shared().eventManager.trigger(eventName: en, information: details)
  }
  
  @objc static func openLink(url: String) {
    NSLog("Trying to open \(url)")
    NSWorkspace.shared.open(NSURL(string: url)! as URL)
  }
  
  /**
   *
   * This function will execute when the launcher shuts down for some reason.
   * Could be either OS or user triggered.
   *
   */
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

extension SwiftApplicationDelegate: NSUserNotificationCenterDelegate {
  func userNotificationCenter(_ center: NSUserNotificationCenter, didActivate notification: NSUserNotification) {
    //popupController.openPopup()
  }
}

extension SwiftApplicationDelegate: AppearanceObserver {
  func changeAppearance(to newAppearance: NSAppearance) {
    //popupController.backgroundView.backgroundColor = newAppearance.isDarkMode ? .windowBackgroundColor : .white
  }
}

