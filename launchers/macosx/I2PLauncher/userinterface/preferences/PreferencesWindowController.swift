//
//  PreferencesWindowController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 07/11/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Cocoa

class PreferencesWindowController: NSWindowController, NSWindowDelegate {

  override func windowDidLoad() {
    super.windowDidLoad()
  
    let visualEffect = NSVisualEffectView()
    visualEffect.blendingMode = .behindWindow
    visualEffect.state = .active
    visualEffect.material = .dark
    //self.window?.contentView = visualEffect
    
    //self.window?.titlebarAppearsTransparent = true
    //self.window?.styleMask.insert(.fullSizeContentView)
    window?.titlebarAppearsTransparent = true
  }
  
  func windowShouldClose(_ sender: NSWindow) -> Bool {
    self.window?.orderOut(sender)
    return false
  }

}
