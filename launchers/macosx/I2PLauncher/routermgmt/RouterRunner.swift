//
//  RouterRunner.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class RouterRunner: NSObject, I2PSubprocess {
  
  var subprocessPath: String?
  var arguments: String?
  var timeWhenStarted: Date?
  
  var currentRunningProcess: Subprocess?
  var currentProcessResults: ExecutionResult?
  
  func findJava() {
    self.subprocessPath = RouterProcessStatus.knownJavaBinPath
  }
  
  let defaultStartupFlags:[String] = [
    "-Xmx512M",
    "-Xms128m",
    "-Djava.awt.headless=true",
    "-Dwrapper.logfile=/tmp/router.log",
    "-Dwrapper.logfile.loglevel=DEBUG",
    "-Dwrapper.java.pidfile=/tmp/routerjvm.pid",
    "-Dwrapper.console.loglevel=DEBUG"
  ]
  
  private func subInit(cmdPath: String?, cmdArgs: String?) {
    // Use this as common init
    self.subprocessPath = cmdPath
    self.arguments = cmdArgs
    if (self.arguments?.isEmpty)! {
      self.arguments = Optional.some(defaultStartupFlags.joined(separator: " "))
    };
    let newArgs:[String] = ["-c ",
                            self.subprocessPath!,
      " ",
      self.arguments!,
    ]
    self.currentRunningProcess = Optional.some(Subprocess.init(executablePath: "/bin/sh", arguments: newArgs))
  }
  
  init(cmdPath: String?, _ cmdArgs: String? = Optional.none) {
    super.init()
    self.subInit(cmdPath: cmdPath, cmdArgs: cmdArgs)
  }
  
  init(coder: NSCoder) {
    super.init()
    self.subInit(cmdPath: Optional.none, cmdArgs: Optional.none)
  }
  
  func execute() {
    if (self.currentRunningProcess != Optional.none!) {
      print("Already executing! Process ", self.toString())
    }
    self.timeWhenStarted = Date()
    RouterProcessStatus.routerStartedAt = self.timeWhenStarted
    
    self.currentProcessResults = self.currentRunningProcess?.execute(captureOutput: true)
  }
  
}
