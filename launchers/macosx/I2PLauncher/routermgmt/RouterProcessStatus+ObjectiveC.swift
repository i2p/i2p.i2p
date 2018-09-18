//
//  RouterProcessStatus+ObjectiveC.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

extension RouterProcessStatus {
  static func createNewRouterProcess(i2pPath: String, javaBinPath: String) {
    let bridge = SBridge()
    let timeWhenStarted = Date()
    RouterProcessStatus.routerStartedAt = timeWhenStarted
    bridge.startupI2PRouter(i2pPath, javaBinPath: javaBinPath)
  }
}
