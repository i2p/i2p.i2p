//
//  RouterDeployer.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class RouterDeployer: NSObject, I2PSubprocess {
  var subprocessPath: String?
  var timeWhenStarted: Date?
  
  var arguments: String?
  
  func findJava() {
    //
  }
  
  let javaBinaryPath = RouterProcessStatus.knownJavaBinPath
  
  let defaultFlagsForExtractorJob:[String] = [
    "-Xmx512M",
    "-Xms128m",
    "-Djava.awt.headless=true"
  ]
}
