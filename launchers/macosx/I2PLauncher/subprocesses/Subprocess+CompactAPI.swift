//
//  Subprocess+CompactAPI.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


// MARK: - Compact API
extension Subprocess {
  
  /**
   Executes a subprocess and wait for completion, returning the output. If there is an error in creating the task,
   it immediately exits the process with status 1
   - returns: the output as a String
   - note: in case there is any error in executing the process or creating the task, it will halt execution. Use
   the constructor and `output` instance method for a more graceful error handling
   */
  public static func output(
    executablePath: String,
    _ arguments: String...,
    workingDirectory: String = ".") -> String {
    
    let process = Subprocess.init(executablePath: executablePath, arguments: arguments, workingDirectory: workingDirectory)
    guard let result = process.execute(captureOutput: true) else {
      Error.die(arguments: "Can't execute \"\(process)\"")
    }
    if result.status != 0 {
      let errorLines = result.errors == "" ? "" : "\n" + result.errors
      Error.die(arguments: "Process \"\(process)\" returned status \(result.status)", errorLines)
    }
    return result.output
  }
  
  /**
   Executes a subprocess and wait for completion, returning the execution result. If there is an error in creating the task,
   it immediately exits the process with status 1
   - returns: the execution result
   - note: in case there is any error in executing the process or creating the task, it will halt execution. Use
   the constructor and `execute` instance method for a more graceful error handling
   */
  public static func execute(
    executablePath: String,
    _ arguments: String...,
    workingDirectory: String = ".") -> ExecutionResult {
    
    let process = Subprocess.init(executablePath: executablePath, arguments: arguments, workingDirectory: workingDirectory)
    guard let result = process.execute(captureOutput: true) else {
      Error.die(arguments: "Can't execute \"\(process)\"")
    }
    return result
  }
  
  /**
   Executes a subprocess and wait for completion, returning the output as an array of lines. If there is an error
   in creating or executing the task, it immediately exits the process with status 1
   - returns: the output as a String
   - note: in case there is any error in executing the process or creating the task, it will halt execution. Use
   the constructor and `output` instance method for a more graceful error handling
   */
  public static func outputLines(
    executablePath: String,
    _ arguments: String...,
    workingDirectory: String = ".") -> [String] {
    
    let process = Subprocess.init(executablePath: executablePath, arguments: arguments, workingDirectory: workingDirectory)
    guard let result = process.execute(captureOutput: true) else {
      Error.die(arguments: "Can't execute \"\(process)\"")
    }
    if result.status != 0 {
      let errorLines = result.errors == "" ? "" : "\n" + result.errors
      Error.die(arguments: "Process \"\(process)\" returned status \(result.status)", errorLines)
    }
    return result.outputLines
  }
  
  /**
   Executes a subprocess and wait for completion, returning the exit status. If there is an error in creating the task,
   it immediately exits the process with status 1
   - returns: the output as a String
   - note: in case there is any error in launching the process or creating the task, it will halt execution. Use
   the constructor and the `run` instance method for a more graceful error handling
   */
  public static func run(
    executablePath: String,
    _ arguments: String...,
    workingDirectory: String = ".") -> Int32 {
    
    let process = Subprocess.init(executablePath: executablePath, arguments: arguments, workingDirectory: workingDirectory)
    guard let result = process.run() else {
      Error.die(arguments: "Can't execute \"\(process)\"")
    }
    return result
  }
  
  /**
   Executes a subprocess and wait for completion. If there is an error in creating the task, or if the tasks
   returns an exit status other than 0, it immediately exits the process with status 1
   - note: in case there is any error in launching the process or creating the task, or if the task exists with a exit status other than 0, it will halt execution. Use
   the constructor and `run` instance method for a more graceful error handling
   */
  public static func runOrDie(
    executablePath: String,
    _ arguments: String...,
    workingDirectory: String = ".") {
    
    let process =  Subprocess.init(executablePath: executablePath, arguments: arguments, workingDirectory: workingDirectory)
    guard let result = process.run() else {
      Error.die(arguments: "Can't execute \"\(process)\"")
    }
    if result != 0 {
      Error.die(arguments: "Process \"\(process)\" returned status \(result)")
    }
  }
}
