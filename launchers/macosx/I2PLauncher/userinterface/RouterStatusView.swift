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
  
  @IBOutlet var quickControlView: NSView?
  @IBOutlet var routerStartStopButton: NSButton?
  
  @objc func actionBtnStartRouter(_ sender: Any?) {
    NSLog("START ROUTER")
    if (!(RouterManager.shared().getRouterTask()?.isRunning())!) {
      SBridge.sharedInstance().startupI2PRouter(RouterProcessStatus.i2pDirectoryPath, javaBinPath: RouterProcessStatus.knownJavaBinPath!)
    }
    RouterManager.shared().updateState()
  }
  
  @objc func actionBtnStopRouter(_ sender: Any?) {
    NSLog("STOP ROUTER")
    if ((RouterManager.shared().getRouterTask()?.isRunning())!) {
      NSLog("Found running router")
      RouterManager.shared().getRouterTask()?.requestShutdown()
      RouterManager.shared().updateState()
    }
  }
  
  @objc func actionBtnRestartRouter(sender: Any?) {
    if ((RouterManager.shared().getRouterTask()?.isRunning())!) {
      RouterManager.shared().getRouterTask()?.requestRestart()
    } else {
      NSLog("Can't restart a non running router, start it however...")
      SBridge.sharedInstance().startupI2PRouter(RouterProcessStatus.i2pDirectoryPath, javaBinPath: RouterProcessStatus.knownJavaBinPath!)
    }
    RouterManager.shared().updateState()
  }
  
  
  
  override func viewWillDraw() {
    super.viewWillDraw()
    if (RouterStatusView.instance != nil) {
      RouterStatusView.instance = self
    }
    self.setRouterStatusLabelText()
  }
  
  func setRouterStatusLabelText() {
    if (RouterProcessStatus.isRouterRunning) {
      routerStatusLabel?.cell?.stringValue = "Router status: Running"
      routerStartStopButton?.title = "Stop Router"
      routerStartStopButton?.action = #selector(self.actionBtnStopRouter(_:))
    } else {
      routerStatusLabel?.cell?.stringValue = "Router status: Not running"
      routerStartStopButton?.title = "Start Router"
      routerStartStopButton?.action = #selector(self.actionBtnStartRouter(_:))
    }
    routerStartStopButton?.needsDisplay = true
    routerStartStopButton?.target = self
    quickControlView?.needsDisplay = true
    
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
    self.setRouterStatusLabelText()
  }
  
  required init?(coder decoder: NSCoder) {
    super.init(coder: decoder)
    self.setRouterStatusLabelText()
  }
  
}
