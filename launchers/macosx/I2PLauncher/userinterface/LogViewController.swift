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
      
      DispatchQueue.main.async(execute: {
        let previousOutput = self.textFieldView?.string ?? ""
        let nextOutput = previousOutput + "\n" + outputString
        self.textFieldView?.string = nextOutput
        
        let range = NSRange(location:nextOutput.characters.count,length:0)
        self.textFieldView?.scrollRangeToVisible(range)
        
      })
      
      self.outputPipe?.fileHandleForReading.waitForDataInBackgroundAndNotify()
    }
    
  }
}


