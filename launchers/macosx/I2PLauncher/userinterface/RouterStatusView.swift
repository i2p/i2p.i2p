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
  
  var isFirefoxEnabled = false
  
  @IBOutlet var routerStatusLabel: NSTextField?
  @IBOutlet var routerVersionLabel: NSTextField?
  @IBOutlet var routerStartedByLabel: NSTextField?
  @IBOutlet var routerUptimeLabel: NSTextField?
  @IBOutlet var routerPIDLabel: NSTextField?
  
  
  @IBOutlet var quickControlView: NSView?
  @IBOutlet var routerStartStopButton: NSButton?
  @IBOutlet var openConsoleButton: NSButton?
  @IBOutlet var launchFirefoxButton: NSButton?
  
  
  @objc func actionBtnOpenConsole(_ sender: Any?) {
    SwiftApplicationDelegate.openLink(url: "http://localhost:7657")
  }
  
  @objc func actionBtnStartRouter(_ sender: Any?) {
    NSLog("Router start clicked")
    (sender as! NSButton).isTransparent = true
    let routerStatus = RouterRunner.launchAgent?.status()
    DispatchQueue(label: "background_start").async {
      switch routerStatus {
      case .loaded?:
        RouterManager.shared().routerRunner.StartAgent(RouterRunner.launchAgent)
      case .unloaded?:
        do {
          try LaunchAgentManager.shared.load(RouterRunner.launchAgent!)
          RouterManager.shared().routerRunner.StartAgent(RouterRunner.launchAgent)
        } catch {
          RouterManager.shared().eventManager.trigger(eventName: "router_exception", information: error)
        }
        break
      default:
        break
      }
      DispatchQueue.main.async {
        self.reEnableButton()
      }
    }
  }
  
  @objc func actionBtnStopRouter(_ sender: Any?) {
    NSLog("Router stop clicked")
    DispatchQueue(label: "background_shutdown").async {
      RouterManager.shared().routerRunner.StopAgent({
        RouterProcessStatus.isRouterRunning = false
        RouterProcessStatus.isRouterChildProcess = false
        NSLog("Router should be stopped by now.")
      })
      // Wait for it to die.
      
    }
    RouterManager.shared().eventManager.trigger(eventName: "toggle_popover")
    self.reEnableButton()
  }
  
  @objc func actionBtnLaunchFirefox(_ sender: Any?) {
    DispatchQueue.global(qos: .background).async {
      Swift.print("Starting firefox")
      let _ = FirefoxManager.shared().executeFirefox()
    }
  }
  
  func restartFn() {
    RouterManager.shared().routerRunner.StopAgent({
      sleep(30)
      RouterManager.shared().routerRunner.StartAgent()
    })
  }
  
  func handlerRouterStart(information:Any?) {
    NSLog("Triggered handlerRouterStart")
    NSLog("PID2! %@", information as! String)
    routerPIDLabel?.cell?.stringValue = "Router PID: "+(information as! String)
    routerPIDLabel?.needsDisplay = true
    routerStatusLabel?.cell?.stringValue = "Router status: Running"
    RouterManager.shared().lastRouterPid = (information as? String)
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
  
  func setupFirefoxBtn() {
    DispatchQueue.global(qos: .background).async {
      if (FirefoxManager.shared().IsFirefoxFound() && !self.isFirefoxEnabled) {
        Swift.print("Enabling Firefox Launch Button")
        DispatchQueue.main.async {
          self.isFirefoxEnabled = true
          self.launchFirefoxButton?.isEnabled = true
          self.launchFirefoxButton?.isTransparent = false
          self.launchFirefoxButton?.needsDisplay = true
          self.launchFirefoxButton?.action = #selector(self.actionBtnLaunchFirefox(_:))
          self.launchFirefoxButton?.target = self
        }
      }
    }
  }
  
  override func viewWillDraw() {
    super.viewWillDraw()
    if (RouterStatusView.instance != nil) {
      RouterStatusView.instance = self
    }
    self.reEnableButton()
    openConsoleButton?.cell?.action = #selector(self.actionBtnOpenConsole(_:))
    openConsoleButton?.cell?.target = self
    
  }
  
  func handleRouterStop() {
    routerPIDLabel?.cell?.stringValue = "Router PID: Not running"
    RouterManager.shared().lastRouterPid = nil
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
    routerStartStopButton?.target = self
    let staticStartedByLabelText = "Router started by launcher? "
    let staticIsRunningLabelText = "Router status: "
    let staticRouterVersionLabelText = "Router version: "
    let staticRouterPidLabelText = "Router PID: "
    
    // Use default here to avoid any potential crashes with force unwrapping
    let currentStatus : AgentStatus = RouterRunner.launchAgent?.status() ?? AgentStatus.unloaded
    if currentStatus == AgentStatus.loaded || currentStatus == AgentStatus.unloaded  {
      routerStatusLabel?.cell?.stringValue = staticIsRunningLabelText+"Not running"
    } else {
      routerStatusLabel?.cell?.stringValue = staticIsRunningLabelText+"Running"
    }
    
    if RouterProcessStatus.isRouterChildProcess {
      routerStartedByLabel?.cell?.stringValue = staticStartedByLabelText+"Yes"
    } else {
      routerStartedByLabel?.cell?.stringValue = staticStartedByLabelText+"No"
    }
    
    // Try to display PID - if not, the string behind ?? is used as "default"
    let tmpPidText = RouterManager.shared().lastRouterPid ?? "Not running"
    routerPIDLabel?.cell?.stringValue = staticRouterPidLabelText+tmpPidText
    
    if let version = RouterProcessStatus.routerVersion {
      routerVersionLabel?.cell?.stringValue = staticRouterVersionLabelText + version
    } else {
      routerVersionLabel?.cell?.stringValue = staticRouterVersionLabelText + "Still unknown"
    }
    
    if let routerStartTime = RouterProcessStatus.routerStartedAt {
      routerUptimeLabel?.cell?.stringValue = "Uptime: Router started " + DateTimeUtils.timeAgoSinceDate(date: NSDate(date: routerStartTime), numericDates: false)
    } else {
      routerUptimeLabel?.cell?.stringValue = "Uptime: Router isn't running"
    }
    
    // Needs display function alerts the rendrerer that the UI parts need to be re-drawed.
    routerStartStopButton?.needsDisplay = true
    quickControlView?.needsDisplay = true
    routerUptimeLabel?.needsDisplay = true
    routerVersionLabel?.needsDisplay = true
    routerStartedByLabel?.needsDisplay = true
    routerPIDLabel?.needsDisplay = true
  }
  
  
  init() {
    let c = NSCoder()
    super.init(coder: c)!
    self.setupObservers()
    self.setupFirefoxBtn()
    self.toggleSetButtonStart()
    self.reEnableButton()
  }
  
  required init?(coder decoder: NSCoder) {
    super.init(coder: decoder)
    self.setupObservers()
    self.setupFirefoxBtn()
    self.toggleSetButtonStart()
    self.reEnableButton()
  }
  
}
