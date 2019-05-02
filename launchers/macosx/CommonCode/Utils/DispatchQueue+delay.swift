//
//  DispatchQueue+delay.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 24/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Foundation

extension DispatchQueue {
  static func delay(_ delay: DispatchTimeInterval, closure: @escaping () -> ()) {
    DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: closure)
  }
}
