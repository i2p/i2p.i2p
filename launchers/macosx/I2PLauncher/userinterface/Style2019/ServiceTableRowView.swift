//
//  ServiceTableRowView.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 19/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Cocoa

class ServiceTableRowView: NSTableRowView {
  var showSeparator = true
  var gradient: CAGradientLayer?
  
  override func layout() {
    super.layout()
    
    let width = frame.size.width
    let height = frame.size.height
    
    let gradient = self.gradient ?? CAGradientLayer()
    
    gradient.isHidden = !showSeparator
    
    self.wantsLayer = true
    self.layer?.insertSublayer(gradient, at: 0)
    self.gradient = gradient
    
    let separatorColor = NSColor.quaternaryLabelColor.cgColor
    gradient.colors = [NSColor.clear.cgColor, separatorColor, separatorColor, separatorColor, NSColor.clear.cgColor]
    gradient.locations = [0, 0.3, 0.5, 0.70, 1]
    gradient.startPoint = CGPoint(x: 0, y: 0.5)
    gradient.endPoint = CGPoint(x: 1, y: 0.5)
    gradient.frame = CGRect(x: 0, y: height - 1, width: width, height: 1)
  }
}

