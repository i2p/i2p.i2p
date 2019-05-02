//
//  SwitchableTableViewController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 30/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Cocoa

protocol SwitchableTableViewController {
  var hidden: Bool { get set }
  
  mutating func show()
  mutating func hide()
  func willShow()
  func willHide()
}

extension SwitchableTableViewController {
  mutating func show() {
    self.hidden = false
    self.willShow()
  }
  
  mutating func hide() {
    self.hidden = true
    self.willHide()
  }
}

