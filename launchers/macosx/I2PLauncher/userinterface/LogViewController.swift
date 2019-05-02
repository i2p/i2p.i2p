//
//  LogViewController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation
import AppKit

class LogViewerViewController : NSTabViewItem {
  
  @IBOutlet var scrollView: NSScrollView?
  @IBOutlet var textFieldView: NSTextView?
  
  private var outputPipe : Pipe?
  
  override init(identifier: Any?) {
    super.init(identifier: identifier)
    self.captureStandardOutputAndRouteToTextView()
  }
  
  required init?(coder aDecoder: NSCoder) {
    super.init(coder: aDecoder)
    self.captureStandardOutputAndRouteToTextView()
  }
  
  
  func captureStandardOutputAndRouteToTextView() {
    outputPipe = RouterManager.shared().getRouterTask()?.processPipe
    outputPipe?.fileHandleForReading.waitForDataInBackgroundAndNotify()
    
    NotificationCenter.default.addObserver(forName: NSNotification.Name.NSFileHandleDataAvailable, object: outputPipe?.fileHandleForReading , queue: nil) {
      notification in

      let output = self.outputPipe?.fileHandleForReading.availableData
      let outputString = String(data: output!, encoding: String.Encoding.utf8) ?? ""
      let workTask = DispatchWorkItem {
        let previousOutput = self.textFieldView?.string ?? ""
        let nextOutput = previousOutput + "\n" + outputString
        self.textFieldView?.string = nextOutput
        
        let range = NSRange(location:nextOutput.count,length:0)
        self.textFieldView?.scrollRangeToVisible(range)
      }
      DispatchQueue.main.async(execute: workTask)
      
      // When router stop, stop the stream as well. If not it will go wild and create high cpu load
      RouterManager.shared().eventManager.listenTo(eventName: "router_stop", action: {
        NSLog("Time to cancel stream!")
        workTask.cancel()
      })
      
      
      self.outputPipe?.fileHandleForReading.waitForDataInBackgroundAndNotify()
    }
    
  }
}


