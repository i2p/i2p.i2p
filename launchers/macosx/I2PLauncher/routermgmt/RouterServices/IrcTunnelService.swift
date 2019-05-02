//
//  IrcTunnelService.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 03/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Foundation

class IrcTunnelService : Service {
  
  override func updateStatus(callback: @escaping (BaseService) -> Void) {
    defer { callback(self) }
    
    self.status = ServiceStatus(rawValue: 0)!
    self.message = "Dead"
  }
  
}
