//
//  StatusBarController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 13/03/2018.
//  Copyright © 2018 I2P. All rights reserved.
//

import Foundation
import Cocoa
import Sparkle

@objc class StatusBarController: NSObject, NSMenuDelegate {
  
  let popover = NSPopover()
  let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
  let storyboard = NSStoryboard(name: "Storyboard", bundle: Bundle.main)
  
  let updaterObject: SUUpdater = SUUpdater()
  
  var ctrl : PopoverViewController?
  private static var preferencesController: NSWindowController?
  private static var experimentalConsoleViewController: NSWindowController?

  @IBOutlet var routerStatusTabView: RouterStatusView?
  
  @IBAction func handleNativePrefMenuClicked(_ sender: Any) {
    StatusBarController.launchPreferences(sender)
  }
  
  //var updateObjectRef : SUUpdater?
  
  @objc func handleOpenConsole(_ sender: Any?) {
    SwiftApplicationDelegate.openLink(url: "http://localhost:7657")
  }
  
  static func onExperimentalConsoleViewClick(_ sender: NSButton) {
    if #available(OSX 10.12, *) {
      print("Clicked for Experimental Console WebView")
      if !(experimentalConsoleViewController != nil) {
        let storyboard = NSStoryboard(name: "ConsoleWebView", bundle: Bundle.main)
        experimentalConsoleViewController = storyboard.instantiateInitialController() as? NSWindowController
        print("created experimental console webview controller")
      }
      if (experimentalConsoleViewController != nil) {
        experimentalConsoleViewController!.showWindow(sender)
        print("trying to view: Console WebView")
      }
    } else {
      // Sorry, only OSX >= 10.12
    }
  }
  
  @objc static func launchPreferences(_ sender: Any) {
    print("Preferences clicked")
    if !(preferencesController != nil) {
      let storyboard = NSStoryboard(name: "Preferences", bundle: Bundle.main)
      preferencesController = storyboard.instantiateInitialController() as? NSWindowController
      print("created preferences controller")
    }
    if (preferencesController != nil) {
      NSApp.activate(ignoringOtherApps: true)
      preferencesController!.showWindow(sender)
      print("trying to view: Preferences")
    }
  }
  
  @objc func launchPreferencesStaticProxy(_ sender: Any) {
    StatusBarController.launchPreferences(sender)
  }
  
  static func launchRouterConsole(_ sender: Any) {
    if (!Preferences.shared().featureToggleExperimental) {
      // The normal...
      NSWorkspace.shared.open(URL(string: "http://127.0.0.1:7657")!)
    } else {
      // Experimental
    }
  }
  
  func pidReaction(information:Any?){
    let pidStr = information as! String
    NSLog("Router PID is %@", pidStr)
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
    
    let _ = FirefoxManager.shared().tryAutoDetect()
    
    print("Is Firefox found? \(FirefoxManager.shared().IsFirefoxFound())")
    print("Is Firefox profile extracted at \(Preferences.shared()["I2Pref_firefoxProfilePath"] as! String)? \(FirefoxManager.shared().IsProfileExtracted())")
    if (!FirefoxManager.shared().IsProfileExtracted()) {
      let _ = FirefoxManager.shared().unzipProfile()
    }
    
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
    NSLog("Sender: @%", realSender)
  }
  
  @IBAction func quitClicked(_ sender: NSMenuItem) {
    NSApplication.shared.terminate(self)
  }
  
  @objc func checkUpdateClicked(_ sender: NSMenuItem) {
    //DispatchQueue(label: "updaterqueue").async {}
    self.updaterObject.checkForUpdates(sender)
  }
  
}

/**
 *
 * Here you find menu & popover trigger related code.
 *
 */

extension StatusBarController {
  
  // This is the construction of the context (right-click) menu spawned at the system tray icon.
  @objc func constructMenu() -> NSMenu {
    let menu = NSMenu()
    
    let updateMenuItem = NSMenuItem(title: "Check for updates", action: #selector(self.checkUpdateClicked(_:)), keyEquivalent: "U")
    updateMenuItem.isEnabled = true
    updateMenuItem.target = self
    
    
    let preferencesMenuItem = NSMenuItem(title: "Preferences", action: #selector(self.launchPreferencesStaticProxy(_:)), keyEquivalent: "P")
    preferencesMenuItem.isEnabled = true
    preferencesMenuItem.target = self
    
    menu.addItem(NSMenuItem(title: "Open I2P Console", action: #selector(self.handleOpenConsole(_:)), keyEquivalent: "O"))
    menu.addItem(NSMenuItem.separator())
    menu.addItem(updateMenuItem)
    menu.addItem(preferencesMenuItem)
    menu.addItem(NSMenuItem.separator())
    menu.addItem(NSMenuItem(title: "Quit I2P Launcher", action: #selector(SwiftApplicationDelegate.terminate(_:)), keyEquivalent: "q"))
    
    return menu
  }
  
  @objc func statusBarButtonClicked(sender: NSStatusBarButton) {
    let event = NSApp.currentEvent!
    
    if event.type == NSEvent.EventType.rightMouseUp {
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
