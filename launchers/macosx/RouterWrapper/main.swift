//
//  main.swift
//  RouterWrapper
//
//  Created by Mikal Villa on 24/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Foundation

let applicationsSupportPath: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!

let defaultStartupFlags:[String] = [
  "-Djava.awt.headless=true",
  "".appendingFormat("-Di2p.base.dir=%@", Preferences.shared().i2pBaseDirectory),
  "".appendingFormat("-Dwrapper.logfile=%@/i2p/router.log", applicationsSupportPath.absoluteString),
  "".appendingFormat("-Dwrapper.java.pidfile=%@/i2p/router.pid", applicationsSupportPath.absoluteString),
  "-Dwrapper.logfile.loglevel=DEBUG", // TODO: Allow loglevel to be set from Preferences?
  "-Dwrapper.console.loglevel=DEBUG",
  "net.i2p.router.Router"
]

let javaCliArgs = Preferences.shared().javaCommandPath.splitByWhitespace()

let daemonPath = javaCliArgs[0]
let arguments = defaultStartupFlags.joined(separator: " ")

let basePath = Preferences.shared().i2pBaseDirectory

let jars = try! FileManager.default.contentsOfDirectory(atPath: basePath+"/lib")
var classpath:String = "."
for jar in jars {
  if (jar.hasSuffix(".jar")) {
    classpath += ":"+basePath+"/lib/"+jar
  }
}

var cliArgs:[String] = []
cliArgs.append(contentsOf: javaCliArgs.dropFirst())
cliArgs.append(contentsOf: [
  "-cp",
  classpath,
  ])
// This allow java arguments to be passed from the settings
cliArgs.append(contentsOf: Preferences.shared().javaCommandOptions.splitByWhitespace())
cliArgs.append(contentsOf: defaultStartupFlags)

print(cliArgs)

let javaProc = Subprocess.init(executablePath: daemonPath, arguments: cliArgs, workingDirectory: basePath)

let exitCode = javaProc.run()

print("Exited with code \(exitCode)")
