//
//  AppDelegate.swift
//  StartupItemApp
//
//  Created by Mikal Villa on 21/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Cocoa

extension Notification.Name {
  static let killLauncher = Notification.Name("killStartupLauncher")
}

@NSApplicationMain
class AppDelegate: NSObject {
  
  @objc func terminate() {
    NSApp.terminate(nil)
  }
}

extension AppDelegate: NSApplicationDelegate {
  
  func applicationDidFinishLaunching(_ aNotification: Notification) {
    
    let runningApps = NSWorkspace.shared.runningApplications
    let isRunning = !runningApps.filter { $0.bundleIdentifier == Identifiers.mainApplicationBundleId }.isEmpty
    
    if !isRunning {
      DistributedNotificationCenter.default().addObserver(self,
                                                          selector: #selector(self.terminate),
                                                          name: .killLauncher,
                                                          object: Identifiers.mainApplicationBundleId)
      
      let path = Bundle.main.bundlePath as NSString
      var components = path.pathComponents
      components.removeLast()
      components.removeLast()
      components.removeLast()
      components.append("MacOS")
      components.append("I2PLauncher") //main app name
      
      let newPath = NSString.path(withComponents: components)
      
      NSWorkspace.shared.launchApplication(newPath)
    }
    else {
      self.terminate()
    }
  }
}

