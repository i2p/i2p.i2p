//
//  ArrayExtension.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


extension Array where Element: NSAttributedString {
  func joined2(separator: NSAttributedString) -> NSAttributedString {
    var isFirst = true
    return self.reduce(NSMutableAttributedString()) {
      (r, e) in
      if isFirst {
        isFirst = false
      } else {
        r.append(separator)
      }
      r.append(e)
      return r
    }
  }
  
}


