//
//  HttpTunnelService.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 03/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Foundation
import Kanna

class HttpTunnelService : Service {
  
  let dataURL: URL = URL(string: "http://127.0.0.1:7657/i2ptunnel/")!
  
  override func updateStatus(callback: @escaping (BaseService) -> Void) {
    URLSession.shared.dataTask(with: dataURL) { [weak self] data, _, error in
      guard let strongSelf = self else { return }
      defer { callback(strongSelf) }
      
      guard let doc = try? HTML(html: data!, encoding: .utf8) else { return /*strongSelf._fail("Couldn't parse response")*/ }
      
      _ = doc.css("table#clientTunnels > tr.tunnelProperties > td.tunnelStatus").first
      let maxStatus: ServiceStatus = .started
      strongSelf.status = maxStatus
      
      switch maxStatus {
      case .waiting:
        strongSelf.message = "Waiting on router"
      case .started:
        strongSelf.message = "Started"
      case .stopped:
        strongSelf.message = "Stopped"
      case .undetermined:
        strongSelf.message = "Undetermined"
      default:
        strongSelf.message = "Undetermined" /*downComponents.map { $0["name"] as? String }.compactMap { $0 }.joined(separator: ", ")*/
      }
    }.resume()
  }
  
}
