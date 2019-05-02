//
//  RouterRunner.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class RouterRunner: NSObject {
  
  
  var daemonPath: String?
  var arguments: String?
  
  static var launchAgent: LaunchAgent?
  let routerStatus: RouterProcessStatus = RouterProcessStatus()
  
  var currentRunningProcess: Subprocess?
  var currentProcessResults: ExecutionResult?
  
  let domainLabel = String(NSString(format: "%@.I2PRouter", APPDOMAIN))
  
  let plistName = String(NSString(format: "%@.I2PRouter.plist", APPDOMAIN))
  
  let appSupportPath = FileManager.default.urls(for: FileManager.SearchPathDirectory.applicationSupportDirectory, in: FileManager.SearchPathDomainMask.userDomainMask)
  
  override init() {
    super.init()
  }
  
  func SetupAgent() {
    let agent = SetupAndReturnAgent()
    RouterRunner.launchAgent = agent
  }
  
  typealias Async = (_ success: () -> Void, _ failure: (NSError) -> Void) -> Void
  
  func retry(numberOfTimes: Int, _ sleepForS: UInt32, task: () -> Async, success: () -> Void, failure: (NSError) -> Void) {
    task()(success, { error in
      if numberOfTimes > 1 {
        sleep(sleepForS)
        retry(numberOfTimes: numberOfTimes - 1, sleepForS, task: task, success: success, failure: failure)
      } else {
        failure(error)
      }
    })
  }
  
  func SetupAndReturnAgent() -> LaunchAgent {
    
    let applicationsSupportPath: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
    
    let defaultStartupFlags:[String] = [
      "-Djava.awt.headless=true",
      "".appendingFormat("-Di2p.base.dir=%@", Preferences.shared().i2pBaseDirectory),
      "".appendingFormat("-Dwrapper.logfile=%@/i2p/router.log", applicationsSupportPath.absoluteString),
      "".appendingFormat("-Dwrapper.java.pidfile=%@/i2p/router.pid", applicationsSupportPath.absoluteString),
      "-Dwrapper.logfile.loglevel=DEBUG", // TODO: Allow loglevel to be set from Preferences?
      "-Dwrapper.console.loglevel=DEBUG",
      "net.i2p.router.Router"
    ]
    
    let javaCliArgs = Preferences.shared().javaCommandPath.splitByWhitespace()
    
    self.daemonPath = javaCliArgs[0]
    self.arguments = defaultStartupFlags.joined(separator: " ")
    
    let basePath = Preferences.shared().i2pBaseDirectory
    
    let jars = try! FileManager.default.contentsOfDirectory(atPath: basePath+"/lib")
    var classpath:String = "."
    for jar in jars {
      if (jar.hasSuffix(".jar")) {
        classpath += ":"+basePath+"/lib/"+jar
      }
    }
    
    var cliArgs:[String] = [
      self.daemonPath!,
      ]
    cliArgs.append(contentsOf: javaCliArgs.dropFirst())
    cliArgs.append(contentsOf: [
      "-cp",
      classpath,
      ])
    // This allow java arguments to be passed from the settings
    cliArgs.append(contentsOf: Preferences.shared().javaCommandOptions.splitByWhitespace())
    cliArgs.append(contentsOf: defaultStartupFlags)
    let agent = LaunchAgent(label: self.domainLabel,program: cliArgs)
    agent.launchOnlyOnce = false
    agent.keepAlive = false
    agent.workingDirectory = basePath
    agent.userName = NSUserName()
    agent.standardErrorPath = NSString(format: "%@/router.stderr.log", Preferences.shared().i2pLogDirectory) as String
    agent.standardOutPath = NSString(format: "%@/router.stdout.log", Preferences.shared().i2pLogDirectory) as String
    agent.environmentVariables = [
      "PATH": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
      "I2PBASE": basePath,
    ]
    agent.disabled = false
    agent.processType = ProcessType.adaptive
    RouterRunner.launchAgent = agent
    
    // NOTE: I suspect this is better to solve in the application
    agent.runAtLoad = false //Preferences.shared().startRouterOnLauncherStart
    agent.keepAlive = true
    DispatchQueue(label: "background_starter").async {
      do {
        // TODO: Find a better way than sleep
        try LaunchAgentManager.shared.write(agent, called: self.plistName)
        sleep(1)
        try LaunchAgentManager.shared.load(agent)
        sleep(1)
        
        let agentStatus = LaunchAgentManager.shared.status(agent)
        switch agentStatus {
        case .running:
          break
        case .loaded:
          DispatchQueue.main.async {
            RouterManager.shared().eventManager.trigger(eventName: "router_can_start", information: agent)
          }
          break
        case .unloaded:
          break
        }
      } catch {
        DispatchQueue.main.async {
          RouterManager.shared().eventManager.trigger(eventName: "router_setup_error", information: "\(error)")
        }
      }
    }
    return agent
  }
  
  
  func StartAgent(_ information:Any? = nil) {
    if (RouterManager.shared().checkIfRouterCanStart()) {
      let agent = RouterRunner.launchAgent ?? information as! LaunchAgent
      DispatchQueue(label: "background_block").async {
        LaunchAgentManager.shared.start(agent, { (proc) in
          NSLog("Will call onLaunchdStarted")
        })
      }
    } else {
      SBridge.sendUserNotification("Whops! Please wait", formattedMsg: "I'm sorry but it's still something unresolved before we can start the I2P router. Please wait.")
    }
  }
  
  func StopAgent(_ callback: @escaping () -> () = {}) {
    let agentStatus = LaunchAgentManager.shared.status(RouterRunner.launchAgent!)
    DispatchQueue(label: "background_block").async {
      do {
        switch agentStatus {
        case .running:
          // For now we need to use unload to stop it.
          try LaunchAgentManager.shared.unload(RouterRunner.launchAgent!, { (proc) in
            // Called when stop is actually executed
            proc.waitUntilExit()
            DispatchQueue.main.async {
              RouterManager.shared().eventManager.trigger(eventName: "router_stop", information: "ok")
              callback()
            }
          })
          try LaunchAgentManager.shared.load(RouterRunner.launchAgent!)
          break
        case .unloaded:
          // Seems it sometimes get unloaded on stop, we load it again.
          try! LaunchAgentManager.shared.load(RouterRunner.launchAgent!)
          return
        default: break
        }
      } catch {
        NSLog("Error \(error)")
      }
    }
  }
  
  func SetupLaunchd() {
    do {
      try LaunchAgentManager.shared.write(RouterRunner.launchAgent!, called: self.plistName)
      try LaunchAgentManager.shared.load(RouterRunner.launchAgent!)
    } catch {
      RouterManager.shared().eventManager.trigger(eventName: "router_exception", information: error)
    }
  }
  
  func TeardownLaunchd() {
    /*let status = LaunchAgentManager.shared.status(RouterRunner.launchAgent!)
    switch status {
    case .running:*/
      do {
        // Unload no matter previous state!
        try LaunchAgentManager.shared.unload(RouterRunner.launchAgent!)
        
        let plistPath = NSHomeDirectory()+"/Library/LaunchAgents/"+self.plistName
        
        sleep(1)
        if FileManager.default.fileExists(atPath: plistPath) {
          try FileManager.default.removeItem(atPath: plistPath)
        }
      } catch LaunchAgentManagerError.urlNotSet(label: self.domainLabel) {
        Logger.MLog(level:3, "URL not set in launch agent")
      } catch {
        Logger.MLog(level:3, "".appendingFormat("Error in launch agent: %s", error as CVarArg))
        RouterManager.shared().eventManager.trigger(eventName: "router_exception", information: error)
      }
   /*   break
    default: break
    }
    */
  }
  
}
