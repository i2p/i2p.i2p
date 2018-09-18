//
//  Subprocess.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


public class Subprocess {
  /// The path to the executable
  let executablePath : String

  /// Arguments to pass to the executable
  let arguments : [String]

  /// Working directory for the executable
  let workingDirectory : String

  /// Process to pipe to, if any
  let pipeDestination : Subprocess?
  
  public convenience init(
    executablePath: String,
    arguments: [String] = [],
    workingDirectory: String = "."
    ) {
    self.init(executablePath: executablePath,
              arguments: arguments,
              workingDirectory: workingDirectory,
              pipeTo: nil)
  }
  
  public init(
    executablePath: String,
    arguments: [String] = [],
    workingDirectory: String = ".",
    pipeTo: Subprocess?
    ) {
    self.executablePath = executablePath
    self.arguments = arguments
    self.workingDirectory = workingDirectory
    self.pipeDestination = pipeTo
  }
  
  /**
   Returns a subprocess ready to be executed
   - SeeAlso: init(executablePath:arguments:workingDirectory)
   */
  public convenience init(
    _ executablePath: String,
    _ arguments: String...,
    workingDirectory: String = ".") {
    self.init(executablePath: executablePath, arguments: arguments, workingDirectory: workingDirectory, pipeTo: nil)
  }
}

// Public API
extension Subprocess {
  
  /**
   Executes the subprocess and wait for completition, returning the exit status
   - returns: the termination status, or nil if it was not possible to execute the process
   */
  public func run() -> Int32? {
    return self.execute(captureOutput: false)?.status
  }
  
  /**
   Executes the subprocess and wait for completion, returning the output
   - returns: the output of the process, or nil if it was not possible to execute the process
   - warning: the entire output will be stored in a String in memory
   */
  public func output() -> String? {
    return self.execute(captureOutput: true)?.output
  }
  
  /**
   Executes the subprocess and wait for completition, returning the exit status
   - returns: the execution result, or nil if it was not possible to execute the process
   */
  public func execute(captureOutput: Bool = false) -> ExecutionResult? {
    return buildPipeline(captureOutput: captureOutput).run()
  }
}

// Piping of STDIN, STDERR and STDOUT
extension Subprocess {
  
  /// Pipes the output to this process to another process.
  /// Will return a new subprocess, you should execute that subprocess to
  /// run the entire pipe
  public func pipe(to destination: Subprocess) -> Subprocess {
    let downstreamProcess : Subprocess
    if let existingPipe = self.pipeDestination {
      downstreamProcess = existingPipe.pipe(to: destination)
    } else {
      downstreamProcess = destination
    }
    return Subprocess(executablePath: self.executablePath, arguments: self.arguments, workingDirectory: self.workingDirectory, pipeTo: downstreamProcess)
  }
}

public func | (lhs: Subprocess, rhs: Subprocess) -> Subprocess {
  return lhs.pipe(to: rhs)
}

public func | (lhs: String, rhs: String) -> String {
  return "(\(lhs)\(rhs))"
}

// MARK: - Process execution
public enum SubprocessError : LocalizedError {
  case Error(status: Int, message: String)
}

extension Subprocess {
  
  /// Returns the task to execute
  private func task() -> Process {
    let task = Process()
    task.launchPath = self.executablePath
    task.arguments = self.arguments
    task.currentDirectoryPath = self.workingDirectory
    return task
  }
  
  /// Returns the task pipeline for all the downstream processes
  public func buildPipeline(captureOutput: Bool, input: AnyObject? = nil) -> TaskPipeline {
    let task = self.task()
    
    if let inPipe = input {
      task.standardInput = inPipe
    }
    
    if let downstreamProcess = self.pipeDestination {
      let downstreamPipeline = downstreamProcess.buildPipeline(captureOutput: captureOutput, input: task.standardOutput as AnyObject)
      return downstreamPipeline.addToHead(task: task)
    }
    return TaskPipeline(task: task, captureOutput: captureOutput)
  }
}

// Description for pretty print etc.
extension Subprocess  : CustomStringConvertible {
  
  public var description : String {
    return self.executablePath
      + (self.arguments.count > 0
        ? " " + self.arguments
          .map { $0.replace(target: "\\ ", withString: " ") }
          .joined(separator: " ")
        : ""
      )
      + (self.pipeDestination != nil ? " | " + self.pipeDestination!.description : "" )
  }
}
