//
//  StatusBarController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 13/03/2018.
//  Copyright © 2018 I2P. All rights reserved.
//

import Foundation
import Cocoa

@objc class StatusBarController: NSObject, NSMenuDelegate {
  
  let popover = NSPopover()
  let statusItem = NSStatusBar.system().statusItem(withLength: NSVariableStatusItemLength)
  let storyboard = NSStoryboard(name: "Storyboard", bundle: Bundle.main)
  
  var ctrl : PopoverViewController?

  @IBOutlet var routerStatusTabView: RouterStatusView?
  
  //var updateObjectRef : SUUpdater?
  
  @objc func handleOpenConsole(_ sender: Any?) {
    SwiftMainDelegate.openLink(url: "http://localhost:7657")
  }
  
  @objc func constructMenu() -> NSMenu {
    let menu = NSMenu()
    
    /*let updateMenuItem = NSMenuItem(title: "Check for updates", action: #selector(self.updateObjectRef?.checkForUpdates(_:)), keyEquivalent: "U")
    updateMenuItem.isEnabled = true
    */
    menu.addItem(NSMenuItem(title: "Open I2P Console", action: #selector(self.handleOpenConsole(_:)), keyEquivalent: "O"))
    menu.addItem(NSMenuItem.separator())
    //menu.addItem(updateMenuItem)
    menu.addItem(NSMenuItem.separator())
    menu.addItem(NSMenuItem(title: "Quit I2P Launcher", action: #selector(SwiftMainDelegate.terminate(_:)), keyEquivalent: "q"))
    
    return menu
  }
  
  func pidReaction(information:Any?){
    let pidStr = information as! String
    NSLog("PID! %@", pidStr)
    showPopover(sender: nil)
    RouterManager.shared().lastRouterPid = pidStr
    self.ctrl?.getRouterStatusView()?.needsDisplay = true
  }
  
  func event_toggle(information:Any?) {
    self.togglePopover(sender: self)
  }

  
  override init() {
    super.init()
    self.ctrl = PopoverViewController.freshController()
    popover.contentViewController = self.ctrl
    RouterManager.shared().eventManager.listenTo(eventName: "router_pid", action: pidReaction)
    
    RouterManager.shared().eventManager.listenTo(eventName: "toggle_popover", action: event_toggle)
    
    if let button = statusItem.button {
      button.image = NSImage(named:"StatusBarButtonImage")
      button.toolTip = "I2P Launch Manager"
      button.target = self
      button.action = #selector(self.statusBarButtonClicked(sender:))
      button.sendAction(on: [.leftMouseUp, .rightMouseUp])
    }
  }
  
  @IBAction func openConsoleClicked(_ sender: Any) {
    NSLog("openConsoleClicked got clicked")
    let realSender = sender as! NSMenuItem
    NSWorkspace.shared().open(URL(string: "http://127.0.0.1:7657")!)
    NSLog("Sender: @%", realSender)
  }
  
  @IBAction func quitClicked(_ sender: NSMenuItem) {
    NSApplication.shared().terminate(self)
  }
  
  // Submenu
  
  @IBAction func startRouterClicked(_ sender: NSMenuItem) {
    
  }
  
  @IBAction func restartRouterClicked(_ sender: NSMenuItem) {
    
  }
  
  @IBAction func stopRouterClicked(_ sender: NSMenuItem) {
    
  }
  
  func statusBarButtonClicked(sender: NSStatusBarButton) {
    let event = NSApp.currentEvent!
    
    if event.type == NSEventType.rightMouseUp {
      closePopover(sender: nil)
      
      let ctxMenu = constructMenu()
      
      statusItem.menu = ctxMenu
      statusItem.popUpMenu(ctxMenu)
      
      // This is critical, otherwise clicks won't be processed again
      statusItem.menu = nil
    } else {
      togglePopover(sender: nil)
    }
  }
  
  func togglePopover(sender: AnyObject?) {
    if popover.isShown {
      closePopover(sender: sender)
    } else {
      showPopover(sender: sender)
    }
  }
  
  func showPopover(sender: AnyObject?) {
    if let button = statusItem.button {
      let inst = RouterStatusView.getInstance()
      if (inst != nil) {
        if (inst != Optional.none) { RouterStatusView.getInstance()?.setRouterStatusLabelText() }
      }
      popover.show(relativeTo: button.bounds, of: button, preferredEdge: NSRectEdge.minY)
    }
  }
  
  func closePopover(sender: AnyObject?) {
    popover.performClose(sender)
  }
  
  
}

