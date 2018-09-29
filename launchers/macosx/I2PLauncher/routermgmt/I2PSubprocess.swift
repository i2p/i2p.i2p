//
//  I2PSubprocess.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

protocol I2PSubprocess {
  var subprocessPath: String? { get set }
  var arguments: String? { get set }
  var timeWhenStarted: Date? { get set }
  
  func findJava();
  
}

extension I2PSubprocess {
  func toString() -> String {
    let jp = self.subprocessPath!
    let args = self.arguments!
    
    var presentation:String = "I2PSubprocess[ cmd="
    presentation += jp
    presentation += " , args="
    presentation += args
    presentation += "]"
    return presentation
  }
}
