//
//  TaskPipeline.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


// Extend the stdlib with the extension bellow
extension Process {
  /// Launches a task, captures any objective-c exception and relaunches it as Swift error
  public func launchCapturingExceptions() throws {
    if let exception = AppleStuffExecuteWithPossibleExceptionInBlock({
      self.launch()
    }) {
      let reason = exception.reason ?? "unknown error"
      throw SubprocessError.Error(status: -1, message: reason)
    }
  }
}

/// A pipeline of tasks, connected in a cascade pattern with pipes
public struct TaskPipeline {
  
  /// List of tasks in the pipeline
  let tasks: [Process]
  
  /// Output pipe
  let outputPipe: Pipe?
  
  /// Whether the pipeline should capture output to stdErr and stdOut
  let captureOutput : Bool
  
  /// Adds a task to the head of the pipeline, that is, the task will provide the input
  /// for the first task currently on the head of the pipeline
  func addToHead(task: Process) -> TaskPipeline {
    guard let firstTask = tasks.first else {
      fatalError("Expecting at least one task")
    }
    let inoutPipe = Pipe()
    firstTask.standardInput = inoutPipe
    task.standardOutput = inoutPipe
    
    var errorPipe : Pipe?
    if self.captureOutput {
      errorPipe = Pipe()
      task.standardError = errorPipe
    }
    return TaskPipeline(tasks: [task] + self.tasks, outputPipe: self.outputPipe, captureOutput: self.captureOutput)
  }
  
  /// Start all tasks in the pipeline, then wait for them to complete
  /// - returns: the return status of the last process in the pipe, or nil if there was an error
  func run() -> ExecutionResult? {
    
    let runTasks = launchAndReturnNotFailedTasks()
    if runTasks.count != self.tasks.count {
      // dropped a task? it's because it failed to start, so error
      return nil
    }
    runTasks.forEach { $0.waitUntilExit() }
    
    // exit status
    let exitStatuses = runTasks.map { $0.terminationStatus }
    guard captureOutput else {
      return ExecutionResult(pipelineStatuses: exitStatuses)
    }
    
    // output
    let errorOutput = runTasks.map { task -> String in
      guard let errorPipe = task.standardError as? Pipe else { return "" }
      let readData = errorPipe.fileHandleForReading.readDataToEndOfFile()
      return String(data: readData, encoding: String.Encoding.utf8)!
    }
    let output = String(data: self.outputPipe!.fileHandleForReading.readDataToEndOfFile(), encoding: String.Encoding.utf8)!
    return ExecutionResult(pipelineStatuses: exitStatuses, pipelineErrors: errorOutput, output: output)
  }
  
  /// Run all tasks and return the tasks that did not fail to launch
  private func launchAndReturnNotFailedTasks() -> [Process] {
    return self.tasks.compactMap { task -> Process? in
      do {
        try task.launchCapturingExceptions()
        return task
      } catch {
        return nil
      }
    }
  }
  
  init(task: Process, captureOutput: Bool) {
    self.tasks = [task]
    self.captureOutput = captureOutput
    if captureOutput {
      self.outputPipe = Pipe()
      task.standardOutput = self.outputPipe
      let errorPipe = Pipe()
      task.standardError = errorPipe
    } else {
      self.outputPipe = nil
    }
  }
  
  private init(tasks: [Process], outputPipe: Pipe?, captureOutput: Bool) {
    self.tasks = tasks
    self.outputPipe = outputPipe
    self.captureOutput = captureOutput
  }
}

