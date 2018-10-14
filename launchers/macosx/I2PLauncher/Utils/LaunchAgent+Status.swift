//
//  LaunchAgent+Status.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 05/10/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

public enum AgentStatus: Equatable {
  
  case running(pid: Int)
  case loaded
  case unloaded
  
  public static func ==(lhs: AgentStatus, rhs: AgentStatus) -> Bool {
    switch (lhs, rhs) {
    case ( let .running(lhpid), let .running(rhpid) ):
      return lhpid == rhpid
    case (.loaded, .loaded):
      return true
    case (.unloaded, .unloaded):
      return true
    default:
      return false
    }
  }
  
}

extension LaunchAgent {
  
  /// Run `launchctl start` on the agent
  ///
  /// Check the status of the job with `.status()`
  public func start(_ callback: ((Process) -> Void)? = nil ) {
    LaunchAgentManager.shared.start(self, callback)
  }
  
  /// Run `launchctl stop` on the agent
  ///
  /// Check the status of the job with `.status()`
  public func stop(_ callback: ((Process) -> Void)? = nil ) {
    LaunchAgentManager.shared.stop(self, callback)
  }
  
  /// Run `launchctl load` on the agent
  ///
  /// Check the status of the job with `.status()`
  public func load() throws {
    try LaunchAgentManager.shared.load(self)
  }
  
  /// Run `launchctl unload` on the agent
  ///
  /// Check the status of the job with `.status()`
  public func unload() throws {
    try LaunchAgentManager.shared.unload(self)
  }
  
  /// Retreives the status of the LaunchAgent from `launchctl`
  ///
  /// - Returns: the agent's status
  public func status() -> AgentStatus {
    return LaunchAgentManager.shared.status(self)
  }
  
}
