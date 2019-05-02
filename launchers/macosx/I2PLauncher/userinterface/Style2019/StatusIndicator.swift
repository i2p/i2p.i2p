//
//  StatusIndicator.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 09/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Cocoa

class StatusIndicator: NSView {
  var checkmarkIcon = CheckmarkIcon()
  var crossIcon = CrossIcon()
  
  var status: ServiceStatus = .started {
    didSet {
      checkmarkIcon.isHidden = status > .stopped
      crossIcon.isHidden = status <= .stopped
      
      switch status {
      case .undetermined: crossIcon.color = StatusColor.gray
      case .waiting: checkmarkIcon.color = StatusColor.gray
      case .started: checkmarkIcon.color = StatusColor.green
      case .notice: checkmarkIcon.color = StatusColor.green
      case .killed: checkmarkIcon.color = StatusColor.red
      case .crashed: checkmarkIcon.color = StatusColor.red
      case .stopped: crossIcon.color = StatusColor.blue
      case .restarting: crossIcon.color = StatusColor.orange
      }
    }
  }
  
  init() {
    super.init(frame: NSRect.zero)
    commonInit()
  }
  
  required init?(coder: NSCoder) {
    super.init(coder: coder)
    commonInit()
  }
  
  private func commonInit() {
    addSubview(checkmarkIcon)
    addSubview(crossIcon)
  }
  
  override func setFrameSize(_ newSize: NSSize) {
    super.setFrameSize(newSize)
    
    checkmarkIcon.frame = bounds
    crossIcon.frame = bounds
  }
}

class StatusColor {
  static var green = NSColor(calibratedRed: 0.36, green: 0.68, blue: 0.46, alpha: 1)
  static var blue = NSColor(calibratedRed: 0.24, green: 0.54, blue: 1, alpha: 0.8)
  static var orange = NSColor.orange
  static var red = NSColor(calibratedRed: 0.9, green: 0.4, blue: 0.23, alpha: 1)
  static var gray = NSColor.tertiaryLabelColor
}
