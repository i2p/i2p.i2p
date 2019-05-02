//
//  I2PRouterService.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 03/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Foundation

class I2PRouterService : Service {
  
  override func updateStatus(callback: @escaping (BaseService) -> Void) {
    //guard let strongSelf = self else { return }
    defer { callback(self) }
    DispatchQueue.main.async {
      self.status = ServiceStatus(rawValue: 0)!
      self.message = "Dead"
    }
    
  }
  
}
