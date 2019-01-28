//
//  FirefoxManager.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 08/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


class FirefoxManager {
  
  var firefoxAppPath = ""
  private var isFirefoxFound = false
  private var isFirefoxProfileExtracted = false
  
  fileprivate func directoryExistsAtPath(_ path: String) -> Bool {
    var isDirectory = ObjCBool(true)
    let exists = FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory)
    return exists && isDirectory.boolValue
  }
  
  func IsFirefoxFound() -> Bool {
    return self.isFirefoxFound
  }
  
  func IsProfileExtracted() -> Bool {
    return self.isFirefoxProfileExtracted
  }
  
  func bundleExecutableSuffixPath() -> String {
    return "/Contents/MacOS/firefox"
  }
  
  func executeFirefox() -> Bool {
    let fullExecPath = "\(self.firefoxAppPath)\(self.bundleExecutableSuffixPath())"
    let firefoxProcess = Subprocess(executablePath: fullExecPath, arguments: [ "-profile", Preferences.shared()["I2Pref_firefoxProfilePath"] as! String, "http://127.0.0.1:7657/home" ])
    DispatchQueue.global(qos: .background).async {
      let proc = firefoxProcess.execute()
    }
    return true
  }

  func tryAutoDetect() -> Bool {
    let expectedPath = Preferences.shared()["I2Pref_firefoxBundlePath"] as! String
   
    self.isFirefoxProfileExtracted = directoryExistsAtPath(Preferences.shared()["I2Pref_firefoxProfilePath"] as! String)
    
    let result = directoryExistsAtPath(expectedPath)
    self.isFirefoxFound = result
    if (result) {
      self.firefoxAppPath = expectedPath
      return true
    }
    return false
  }
  
  
  private static var sharedFirefoxManager: FirefoxManager = {
    let firefoxMgr = FirefoxManager()
    
    return firefoxMgr
  }()
  
  
  class func shared() -> FirefoxManager {
    return sharedFirefoxManager
  }
}

extension FirefoxManager {
  func unzipProfile() -> Bool {
    let resourceUrl = Bundle.main.url(forResource: "profile", withExtension: "tgz")
    let profileTgz = resourceUrl!.path
    let unzipProc = Subprocess(executablePath: "/usr/bin/tar", arguments: ["-xf",profileTgz,"-C",NSString(format: "%@/Library/Application Support/i2p", NSHomeDirectory()) as String])
    DispatchQueue.global(qos: .background).async {
      let proc = unzipProc.execute(captureOutput: true)
      print("Firefox Profile Extraction Errors: \(proc?.errors)")
      print("Firefox Profile Extraction Output: \(proc?.output)")
    }
    return false
  }
}

