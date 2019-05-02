//
//  CustomScrollView.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 07/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Cocoa
import SnapKit

class CustomScrollView: NSScrollView {
  var topConstraint: Constraint?
  
  override var isOpaque: Bool {
    return false
  }
}
