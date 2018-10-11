//
//  DownloadJavaViewController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 30/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import AppKit

class DownloadJavaViewController: NSViewController {
  
  func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                  didFinishDownloadingTo location: URL) {
    guard let httpResponse = downloadTask.response as? HTTPURLResponse,
      (200...299).contains(httpResponse.statusCode) else {
        print ("server error")
        return
    }
    do {
      let documentsURL = try
        FileManager.default.url(for: .documentDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil,
                                create: false)
      let savedURL = documentsURL.appendingPathComponent(
        location.lastPathComponent)
      try FileManager.default.moveItem(at: location, to: savedURL)
    } catch {
      print ("file error: \(error)")
    }
  }
  
}
