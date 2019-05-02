//
//  RouterManager.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 22/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


enum ErrorsInRouterMgmr: Swift.Error {
  case NoJavaFound
  case InvalidVersion
  case NotFound
}

class RouterManager : NSObject {
  
  // MARK: - Properties
  
  static let packedVersion : String = NSDEF_I2P_VERSION
  
  let eventManager = EventManager()
  let routerRunner = RouterRunner()
  
  var logViewStorage: NSTextStorage?
  var lastRouterPid : String? = nil
  private var canRouterStart : Bool = false
  
  private static func handleRouterException(information:Any?) {
    Logger.MLog(level:1,"event! - handle router exception")
    Logger.MLog(level:1,information as? String)
  }
  private static func handleRouterStart(information:Any?) {
    Logger.MLog(level:1,"event! - handle router start")
    RouterProcessStatus.routerStartedAt = Date()
    RouterProcessStatus.isRouterChildProcess = true
    RouterProcessStatus.isRouterRunning = true
  }
  private static func handleRouterAlreadyStarted(information:Any?) {
    Logger.MLog(level:1,"event! - handle router already started");
  }
  private static func handleRouterStop(information:Any?) {
    Logger.MLog(level:1,"event! - handle router stop")
    // TODO: Double check, check if pid stored exists
    RouterProcessStatus.routerStartedAt = nil
    RouterProcessStatus.isRouterChildProcess = false
    RouterProcessStatus.isRouterRunning = false
  }
  private static func handleRouterPid(information:Any?) {
    Logger.MLog(level:1,"".appendingFormat("event! - handle router pid: ", information as! String))
    if (information != nil) {
      let intPid = Int(information as! String)
      print("Router pid is \(String(describing: intPid))..")
    }
  }
  private static func handleRouterVersion(information:Any?) {
    do {
      Logger.MLog(level:1, "".appendingFormat("event! - handle router version: ", information as! String))
      guard let currentVersion : String = information as? String else {
        throw ErrorsInRouterMgmr.InvalidVersion
      }
      if (currentVersion == "Unknown") {
        throw ErrorsInRouterMgmr.InvalidVersion
      }
      if (packedVersion.compare(currentVersion, options: .numeric) == .orderedDescending) {
        Logger.MLog(level:1,"event! - router version: Packed version is newer, gonna re-deploy")
        RouterManager.shared().eventManager.trigger(eventName: "router_must_upgrade", information: "got new version")
      } else {
        Logger.MLog(level:1,"event! - router version: No update needed")
        RouterManager.shared().eventManager.trigger(eventName: "router_can_setup", information: "all ok")
      }
    } catch ErrorsInRouterMgmr.InvalidVersion {
      // This is most likely due to an earlier extract got killed halfway or something
      // Trigger an update
      RouterManager.shared().eventManager.trigger(eventName: "router_must_upgrade", information: "invalid version found")
    } catch {
      // WTF
      NSLog("Fatal error in RouterManager");
      print(error)
    }
  }
  
  private static var sharedRouterManager: RouterManager = {
    let routerManager = RouterManager(detectJavaInstance: DetectJava.shared())
    
    // Configuration
    // ...
    routerManager.updateState()
    
    routerManager.eventManager.listenTo(eventName: "router_start", action: handleRouterStart)
    routerManager.eventManager.listenTo(eventName: "router_stop", action: handleRouterStop)
    routerManager.eventManager.listenTo(eventName: "router_pid", action: handleRouterPid)
    routerManager.eventManager.listenTo(eventName: "router_version", action: handleRouterVersion)
    routerManager.eventManager.listenTo(eventName: "router_exception", action: handleRouterException)
    routerManager.eventManager.listenTo(eventName: "router_already_running", action: handleRouterAlreadyStarted)
    routerManager.eventManager.listenTo(eventName: "router_can_start", action: routerManager.setLauncherReadyToStartRouter)
    routerManager.eventManager.listenTo(eventName: "router_can_setup", action: routerManager.routerRunner.SetupAgent)
    
    //routerManager.eventManager.listenTo(eventName: "launch_agent_running", action: routerManager.routerRunner.onLoadedAgent)
    
    routerManager.eventManager.listenTo(eventName: "launch_agent_running", action: {
      let agent = RouterRunner.launchAgent!
      let agentStatus = agent.status()
      switch agentStatus {
      case .running(let pid):
        DispatchQueue.main.async {
          routerManager.eventManager.trigger(eventName: "router_start", information: String(pid))
          routerManager.routerRunner.routerStatus.setRouterStatus(true)
          routerManager.routerRunner.routerStatus.setRouterRanByUs(true)
          routerManager.eventManager.trigger(eventName: "router_pid", information: String(pid))
        }
        break
        
      default:
        NSLog("wtf, agent status = \(agentStatus)")
        break
      }
    })
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
  
  static func logInfo(format: String, messages: String...) {
    //SBridge.sharedInstance().logMessageWithFormat(0, format, messages)func k(_ x: Int32, _ params: String...) {
    /*withVaList(messages) {
      genericLogger(x, $0)
    }*/
  }
  
  class func shared() -> RouterManager {
    return sharedRouterManager
  }
  
  func setLauncherReadyToStartRouter(_ information: Any?) {
    self.canRouterStart = true
    if (Preferences.shared().startRouterOnLauncherStart) {
      // Trigger the actual start at launch time
      print("Start router on launch preference is enabled - Starting")
      self.routerRunner.StartAgent(information)
    } else {
      print("Start router on launch preference is disabled - Router ready when user are!")
      SBridge.sendUserNotification("I2P Router Ready", formattedMsg: "The I2P Router is ready to be launched, however the autostart is disabled and require user input to start.")
    }
  }
  
  func checkIfRouterCanStart() -> Bool {
    return self.canRouterStart
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
