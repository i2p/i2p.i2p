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
    
        // Implement this method to handle any initialization after your window controller's window has been loaded from its nib file.
    }
  
  func windowShouldClose(_ sender: NSWindow) -> Bool {
    self.window?.orderOut(sender)
    return false
  }

}
