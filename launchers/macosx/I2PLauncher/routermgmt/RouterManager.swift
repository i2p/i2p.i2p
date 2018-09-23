//
//  RouterManager.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 22/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class RouterManager : NSObject {
  
  // MARK: - Properties
  
  static let packedVersion : String = "0.9.36"
  
  let eventManager = EventManager()
  
  var logViewStorage: NSTextStorage?
  
  private static func handleRouterStart(information:Any?) {
    NSLog("event! - handle router start")
    RouterProcessStatus.routerStartedAt = Date()
    RouterProcessStatus.isRouterChildProcess = true
    RouterProcessStatus.isRouterRunning = true
  }
  private static func handleRouterStop(information:Any?) {
    NSLog("event! - handle router stop")
    RouterProcessStatus.routerStartedAt = nil
    RouterProcessStatus.isRouterChildProcess = false
    RouterProcessStatus.isRouterRunning = false
  }
  private static func handleRouterPid(information:Any?) {
    Swift.print("event! - handle router pid: %s", information ?? "")
  }
  private static func handleRouterVersion(information:Any?) {
    Swift.print("event! - handle router version: %s", information ?? "")
    let currentVersion : String = information as! String
    if (packedVersion.compare(currentVersion, options: .numeric) == .orderedDescending) {
      Swift.print("event! - router version: Packed version is newer, gonna re-deploy")
      RouterManager.shared().eventManager.trigger(eventName: "router_must_upgrade", information: "got new version")
    } else {
      Swift.print("event! - router version: No update needed")
      RouterManager.shared().eventManager.trigger(eventName: "router_can_start", information: "all ok")
    }
  }
  
  private static var sharedRouterManager: RouterManager = {
    let inst = DetectJava()
    let routerManager = RouterManager(detectJavaInstance: inst)
    
    // Configuration
    // ...
    routerManager.updateState()
    
    routerManager.eventManager.listenTo(eventName: "router_start", action: handleRouterStart)
    routerManager.eventManager.listenTo(eventName: "router_stop", action: handleRouterStop)
    routerManager.eventManager.listenTo(eventName: "router_pid", action: handleRouterPid)
    routerManager.eventManager.listenTo(eventName: "router_version", action: handleRouterVersion)
    return routerManager
  }()
  
  // MARK: -
  
  let detectJava: DetectJava
  private var routerInstance: I2PRouterTask?{
    //Called after the change
    didSet{
      print("RouterManager.routerInstance did change to ", self.routerInstance ?? "null")
      if (self.routerInstance != nil) {
        RouterProcessStatus.isRouterRunning = (self.routerInstance?.isRouterRunning)!
      }
    }
  };
  
  // Initialization
  
  private init(detectJavaInstance: DetectJava) {
    self.detectJava = detectJavaInstance
  }
  
  // MARK: - Accessors
  
  class func shared() -> RouterManager {
    return sharedRouterManager
  }
  
  func setRouterTask(router: I2PRouterTask) {
    self.routerInstance = router
  }
  
  func getRouterTask() -> I2PRouterTask? {
    return self.routerInstance
  }
  
  func updateState() {
    self.routerInstance = SBridge.sharedInstance()?.currentRouterInstance
  }
  
}
