//
//  RouterProcessStatus.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation
import AppKit

@objc class RouterProcessStatus : NSObject {
  
  /**
   *
   * Why the functions bellow? Because the Objective-C bridge is limited, it can't do Swift "static's" over it.
   *
   **/
 
  @objc func setRouterStatus(_ isRunning: Bool = false) {
    RouterProcessStatus.isRouterRunning = isRunning
  }
  
  @objc func setRouterRanByUs(_ ranByUs: Bool = false) {
    RouterProcessStatus.isRouterChildProcess = ranByUs
  }
  
  @objc func getRouterIsRunning() -> Bool {
    return RouterProcessStatus.isRouterRunning
  }
  
  @objc func getJavaHome() -> String {
    return DetectJava.shared().javaHome
  }
  
  @objc func getJavaViaLibexec() -> Array<String> {
    return DetectJava.shared().getJavaViaLibexecBin()
  }
  
  @objc func triggerEvent(en: String, details: String? = nil) {
    RouterManager.shared().eventManager.trigger(eventName: en, information: details)
  }

  @objc func listenForEvent(eventName: String, callbackActionFn: @escaping ((Any?)->()) ) {
    RouterManager.shared().eventManager.listenTo(eventName: eventName, action: callbackActionFn )
  }
}

extension RouterProcessStatus {
  static var isRouterRunning : Bool = (RouterManager.shared().getRouterTask() != nil)
  static var isRouterChildProcess : Bool = (RouterManager.shared().getRouterTask() != nil)
  static var routerVersion : String? = Optional.none
  static var routerStartedAt : Date? = Optional.none
  static var i2pDirectoryPath : String = Preferences.shared().i2pBaseDirectory
  
  
}

