//
//  RouterStatusView.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 22/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Cocoa

@objc class RouterStatusView : NSView {
  static var instance: RouterStatusView?
  
  static func getInstance() -> RouterStatusView? {
    if (self.instance != Optional.none) {
      return RouterStatusView.instance
    }
    return Optional.none
  }
  
  @IBOutlet var routerStatusLabel: NSTextField?
  @IBOutlet var routerVersionLabel: NSTextField?
  @IBOutlet var routerStartedByLabel: NSTextField?
  @IBOutlet var routerUptimeLabel: NSTextField?
  @IBOutlet var routerPIDLabel: NSTextField?
  
  @IBOutlet var quickControlView: NSView?
  @IBOutlet var routerStartStopButton: NSButton?
  @IBOutlet var openConsoleButton: NSButton?
  
  @objc func actionBtnStartRouter(_ sender: Any?) {
    NSLog("START ROUTER")
    /*if (RouterManager.shared().getRouterTask() == nil) {
      SBridge.sharedInstance().startupI2PRouter(RouterProcessStatus.i2pDirectoryPath)
    }*/
    (sender as! NSButton).isTransparent = true
    let routerStatus = RouterRunner.launchAgent?.status()
    switch routerStatus {
    case .loaded?:
      RouterManager.shared().routerRunner.StartAgent(information: RouterRunner.launchAgent)
    case .unloaded?:
      do {
        try LaunchAgentManager.shared.load(RouterRunner.launchAgent!)
        RouterManager.shared().routerRunner.StartAgent(information: RouterRunner.launchAgent)
      } catch {
        RouterManager.shared().eventManager.trigger(eventName: "router_exception", information: error)
      }
      break
    default:
      break
    }
    self.reEnableButton()
  }
  
  @objc func actionBtnStopRouter(_ sender: Any?) {
    NSLog("STOP ROUTER")
    let routerStatus = RouterRunner.launchAgent?.status()
    switch routerStatus {
    case .running?:
      NSLog("Found running router")
      RouterManager.shared().routerRunner.StopAgent()
      break
    default:
      break
    }
    self.reEnableButton()
  }
  
  @objc func actionBtnRestartRouter(sender: Any?) {
    if (RouterManager.shared().getRouterTask() != nil) {
      //RouterManager.shared().getRouterTask()?.requestRestart()
    } else {
      NSLog("Can't restart a non running router, start it however...")
      //SBridge.sharedInstance().startupI2PRouter(RouterProcessStatus.i2pDirectoryPath)
    }
  }
  
  func handlerRouterStart(information:Any?) {
    print("Triggered handlerRouterStart")
    NSLog("PID2! %@", information as! String)
    routerPIDLabel?.cell?.stringValue = "Router PID: "+(information as! String)
    routerPIDLabel?.needsDisplay = true
    routerStatusLabel?.cell?.stringValue = "Router status: Running"
    self.toggleSetButtonStop()
    self.reEnableButton()
  }
  
  func reEnableButton() {
    let currentStatus : AgentStatus = RouterRunner.launchAgent?.status() ?? AgentStatus.unloaded
    if currentStatus != AgentStatus.loaded && currentStatus != AgentStatus.unloaded  {
      self.toggleSetButtonStop()
    } else {
      self.toggleSetButtonStart()
    }
    routerStartStopButton?.isTransparent = false
    routerStartStopButton?.needsDisplay = true
    self.setRouterStatusLabelText()
  }
  
  func setupObservers() {
    RouterManager.shared().eventManager.listenTo(eventName: "router_start", action: handlerRouterStart)
    RouterManager.shared().eventManager.listenTo(eventName: "router_stop", action: handleRouterStop)
    RouterManager.shared().eventManager.listenTo(eventName: "router_pid", action: handlerRouterStart)
    RouterManager.shared().eventManager.listenTo(eventName: "launch_agent_running", action: reEnableButton)
    RouterManager.shared().eventManager.listenTo(eventName: "launch_agent_unloaded", action: reEnableButton)
    RouterManager.shared().eventManager.listenTo(eventName: "launch_agent_loaded", action: reEnableButton)
  }
  
  override func viewWillDraw() {
    super.viewWillDraw()
    if (RouterStatusView.instance != nil) {
      RouterStatusView.instance = self
    }
    self.reEnableButton()
  }
  
  func handleRouterStop() {
    routerPIDLabel?.cell?.stringValue = "Router PID: Not running"
    self.toggleSetButtonStart()
    reEnableButton()
  }
  
  private func toggleSetButtonStart() {
    routerStatusLabel?.cell?.stringValue = "Router status: Not running"
    routerStartStopButton?.title = "Start Router"
    routerStartStopButton?.action = #selector(self.actionBtnStartRouter(_:))
  }
  
  private func toggleSetButtonStop() {
    routerStatusLabel?.cell?.stringValue = "Router status: Running"
    routerStartStopButton?.title = "Stop Router"
    routerStartStopButton?.action = #selector(self.actionBtnStopRouter(_:))
  }
  
  func setRouterStatusLabelText() {
    routerStartStopButton?.needsDisplay = true
    routerStartStopButton?.target = self
    quickControlView?.needsDisplay = true
    
    do {
      let currentStatus : AgentStatus = RouterRunner.launchAgent!.status()
      if currentStatus == AgentStatus.loaded || currentStatus == AgentStatus.unloaded  {
        routerStatusLabel?.cell?.stringValue = "Router status: Not running"
      } else {
        routerStatusLabel?.cell?.stringValue = "Router status: Running"
      }
    } catch {
      // Ensure it's set even AgentStatus is nil (uninitialized yet..)
      routerStatusLabel?.cell?.stringValue = "Router status: Not running"
    }
    
    let staticStartedByLabelText = "Router started by launcher?"
    if RouterProcessStatus.isRouterChildProcess {
      routerStartedByLabel?.cell?.stringValue = staticStartedByLabelText+" Yes"
    } else {
      routerStartedByLabel?.cell?.stringValue = staticStartedByLabelText+" No"
    }
    routerStartedByLabel?.needsDisplay = true
    
    if let version = RouterProcessStatus.routerVersion {
      routerVersionLabel?.cell?.stringValue = "Router version: " + version
    } else {
      routerVersionLabel?.cell?.stringValue = "Router version: Still unknown"
    }
    if let routerStartTime = RouterProcessStatus.routerStartedAt {
      routerUptimeLabel?.cell?.stringValue = "Uptime: Router started " + DateTimeUtils.timeAgoSinceDate(date: NSDate(date: routerStartTime), numericDates: false)
    } else {
      routerUptimeLabel?.cell?.stringValue = "Uptime: Router isn't running"
    }
    routerUptimeLabel?.needsDisplay = true
  }
  
  
  init() {
    let c = NSCoder()
    super.init(coder: c)!
    self.setupObservers()
    self.toggleSetButtonStart()
    self.reEnableButton()
  }
  
  required init?(coder decoder: NSCoder) {
    super.init(coder: decoder)
    self.setupObservers()
    self.toggleSetButtonStart()
    self.reEnableButton()
  }
  
}
