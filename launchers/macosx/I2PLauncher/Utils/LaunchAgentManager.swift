//
//  LaunchAgentManager.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 07/10/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


public enum LaunchAgentManagerError: Swift.Error {
  case urlNotSet(label: String)
  
  public var localizedDescription: String {
    switch self {
    case .urlNotSet(let label):
      return "The URL is not set for agent \(label)"
    }
  }
}

public class LaunchAgentManager {
  public static let shared = LaunchAgentManager()
  
  static let launchctl = "/bin/launchctl"
  
  var lastState: AgentStatus?
  
  let encoder = PropertyListEncoder()
  let decoder = PropertyListDecoder()
  
  init() {
    encoder.outputFormat = .xml
  }
  
  func launchAgentsURL() throws -> URL {
    let library = try FileManager.default.url(for: .libraryDirectory, in: .userDomainMask, appropriateFor: nil, create: false)
    
    return library.appendingPathComponent("LaunchAgents")
  }
  
  public func read(agent called: String) throws -> LaunchAgent {
    let url = try launchAgentsURL().appendingPathComponent(called)
    
    return try read(from: url)
  }
  
  public func read(from url: URL) throws -> LaunchAgent {
    return try decoder.decode(LaunchAgent.self, from: Data(contentsOf: url))
  }
  
  public func write(_ agent: LaunchAgent, called: String) throws {
    let url = try launchAgentsURL().appendingPathComponent(called)
    
    try write(agent, to: url)
  }
  
  public func write(_ agent: LaunchAgent, to url: URL) throws {
    try encoder.encode(agent).write(to: url)
    
    agent.url = url
  }
  
  public func setURL(for agent: LaunchAgent) throws {
    let contents = try FileManager.default.contentsOfDirectory(
      at: try launchAgentsURL(),
      includingPropertiesForKeys: nil,
      options: [.skipsPackageDescendants, .skipsHiddenFiles, .skipsSubdirectoryDescendants]
    )
    
    contents.forEach { url in
      let testAgent = try? self.read(from: url)
      
      if agent.label == testAgent?.label {
        agent.url = url
        return
      }
    }
    
    
  }
  
}

extension LaunchAgentManager {
  
  /// Run `launchctl start` on the agent
  ///
  /// Check the status of the job with `.status(_: LaunchAgent)`
  public func start(_ agent: LaunchAgent, _ termHandler: ((Process) -> Void)? = nil ) {
    let arguments = ["start", agent.label]
    let proc = Process.launchedProcess(launchPath: LaunchAgentManager.launchctl, arguments: arguments)
    if ((termHandler) != nil) {
      proc.terminationHandler = termHandler
    }
  }
  
  /// Run `launchctl stop` on the agent
  ///
  /// Check the status of the job with `.status(_: LaunchAgent)`
  public func stop(_ agent: LaunchAgent, _ termHandler: ((Process) -> Void)? = nil ) {
    let arguments = ["stop", agent.label]
    let proc = Process.launchedProcess(launchPath: LaunchAgentManager.launchctl, arguments: arguments)
    if ((termHandler) != nil) {
      proc.terminationHandler = termHandler
    }
  }
  
  /// Run `launchctl load` on the agent
  ///
  /// Check the status of the job with `.status(_: LaunchAgent)`
  public func load(_ agent: LaunchAgent, _ termHandler: ((Process) -> Void)? = nil ) throws {
    guard let agentURL = agent.url else {
      throw LaunchAgentManagerError.urlNotSet(label: agent.label)
    }
    
    let arguments = ["load", agentURL.path]
    let proc = Process.launchedProcess(launchPath: LaunchAgentManager.launchctl, arguments: arguments)
    if ((termHandler) != nil) {
      proc.terminationHandler = termHandler
    }
  }
  
  /// Run `launchctl unload` on the agent
  ///
  /// Check the status of the job with `.status(_: LaunchAgent)`
  public func unload(_ agent: LaunchAgent, _ termHandler: ((Process) -> Void)? = nil ) throws {
    guard let agentURL = agent.url else {
      throw LaunchAgentManagerError.urlNotSet(label: agent.label)
    }
    
    let arguments = ["unload", agentURL.path]
    let proc = Process.launchedProcess(launchPath: LaunchAgentManager.launchctl, arguments: arguments)
    if ((termHandler) != nil) {
      proc.terminationHandler = termHandler
    }
  }
  
  /// Retreives the status of the LaunchAgent from `launchctl`
  ///
  /// - Returns: the agent's status
  public func status(_ agent: LaunchAgent) -> AgentStatus {
    
    let launchctlTask = Process()
    let grepTask = Process()
    let cutTask = Process()
    
    launchctlTask.launchPath = "/bin/launchctl"
    launchctlTask.arguments = ["list"]
    
    grepTask.launchPath = "/usr/bin/grep"
    grepTask.arguments = [agent.label]
    
    cutTask.launchPath = "/usr/bin/cut"
    cutTask.arguments = ["-f1"]
    
    let pipeLaunchCtlToGrep = Pipe()
    launchctlTask.standardOutput = pipeLaunchCtlToGrep
    grepTask.standardInput = pipeLaunchCtlToGrep
    
    let pipeGrepToCut = Pipe()
    grepTask.standardOutput = pipeGrepToCut
    cutTask.standardInput = pipeGrepToCut
    
    let pipeCutToFile = Pipe()
    cutTask.standardOutput = pipeCutToFile
    
    let fileHandle: FileHandle = pipeCutToFile.fileHandleForReading as FileHandle
    
    launchctlTask.launch()
    grepTask.launch()
    cutTask.launch()
    
    
    let data = fileHandle.readDataToEndOfFile()
    let stringResult = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .newlines) ?? ""
    
    let em = RouterManager.shared().eventManager
    
    switch stringResult {
    case "-":
      if (self.lastState != AgentStatus.loaded) {
        self.lastState = AgentStatus.loaded
        em.trigger(eventName: "launch_agent_loaded")
      }

      return .loaded
    case "":
      if (self.lastState != AgentStatus.unloaded) {
        self.lastState = AgentStatus.unloaded
        em.trigger(eventName: "launch_agent_unloaded")
      }
      return .unloaded
    default:
      if (self.lastState != AgentStatus.running(pid: Int(stringResult)!)) {
        self.lastState = AgentStatus.running(pid: Int(stringResult)!)
        em.trigger(eventName: "launch_agent_running")
      }
      return .running(pid: Int(stringResult)!)
    }
  }
}
