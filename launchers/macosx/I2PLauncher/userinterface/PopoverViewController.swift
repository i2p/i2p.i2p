//
//  PopoverViewController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Cocoa

class PopoverViewController: NSViewController {
  
  required init?(coder: NSCoder) {
    super.init(coder: coder)
    //super.init(nibName: "UserInterfaces", bundle: Bundle.main)!
    //let nib = NSNib(nibNamed: "UserInterfaces", bundle: Bundle.main)
    
  }
  
  
  override func viewDidLoad() {
    super.viewDidLoad()
    // Do view setup here.
  }
  
  
  
}

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
    (sender as! NSButton).cell?.stringValue = "Stop Router"
    let timeWhenStarted = Date()
    RouterProcessStatus.routerStartedAt = timeWhenStarted
    SwiftMainDelegate.objCBridge.startupI2PRouter(RouterProcessStatus.i2pDirectoryPath, javaBinPath: RouterProcessStatus.knownJavaBinPath!)
  }
  @objc func actionBtnStopRouter(_ sender: Any?) {
    NSLog("STOP ROUTER")
  }
  @objc func actionBtnRestartRouter(sender: Any?) {}
  
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
      routerStartStopButton?.action = #selector(self.actionBtnStopRouter(_:))
    } else {
      routerStatusLabel?.cell?.stringValue = "Router status: Not running"
      routerStartStopButton?.action = #selector(self.actionBtnStartRouter(_:))
    }
    routerStartStopButton?.needsDisplay = true
    routerStartStopButton?.target = self
    quickControlView?.needsDisplay = true
    
    if let version = RouterProcessStatus.routerVersion {
      routerVersionLabel?.cell?.stringValue = "Router version: " + version
    } else {
      routerVersionLabel?.cell?.stringValue = "Router version: Still unknown"
    }
    if let routerStartTime = RouterProcessStatus.routerStartedAt {
      routerUptimeLabel?.cell?.stringValue = "Router has runned for " + DateTimeUtils.timeAgoSinceDate(date: NSDate(date: routerStartTime), numericDates: false)
    }
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


extension PopoverViewController {
  static func freshController() -> PopoverViewController {
    let storyboard = NSStoryboard(name: "Storyboard", bundle: Bundle.main)
    //2.
    let identifier = NSStoryboard.SceneIdentifier(stringLiteral: "PopoverView")
    //3.
    guard let viewcontroller = storyboard.instantiateController(withIdentifier: identifier as String) as? PopoverViewController else {
      fatalError("Why cant i find PopoverViewController? - Check PopoverViewController.storyboard")
    }
    //let viewcontroller = PopoverViewController()
    return viewcontroller
  }
}

