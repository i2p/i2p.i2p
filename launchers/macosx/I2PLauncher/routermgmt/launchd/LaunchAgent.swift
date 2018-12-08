//
//  LaunchAgent.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 05/10/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

public enum ProcessType: String, Codable {
  case standard = "Standard"
  case background = "Background"
  case adaptive = "Adaptive"
  case interactive = "Interactive"
}

public class LaunchAgent: Codable {
  
  public var url: URL? = nil
  
  // Basic Properties
  public var label: String
  public var disabled: Bool? = nil
  public var enableGlobbing: Bool? = nil
  public var program: String? = nil {
    didSet {
      if program != nil {
        programArguments = nil
      }
    }
  }
  
  public var programArguments: [String]? = nil {
    didSet {
      guard let args = programArguments else {
        return
      }
      if args.count == 1 {
        self.program = args.first
        programArguments = nil
      } else {
        program = nil
      }
    }
  }
  
  public var processType: ProcessType? = nil
  
  // Program
  public var workingDirectory: String? = nil
  public var standardOutPath: String? = nil
  public var standardErrorPath: String? = nil
  public var environmentVariables: [String: String]? = nil
  
  // Run Conditions
  public var runAtLoad: Bool? = nil
  public var startInterval: Int? = nil
  public var onDemand: Bool? = nil
  public var keepAlive: Bool? = nil
  public var watchPaths: [String]? = nil
  
  // Security
  public var umask: Int? = nil
  // System Daemon Security
  public var groupName: String? = nil
  public var userName: String? = nil
  public var rootDirectory: String? = nil
  
  
  // Run Constriants
  public var launchOnlyOnce: Bool? = nil
  public var limitLoadToSessionType: [String]? = nil

  public init(label: String, program: [String]) {
    self.label = label
    if program.count == 1 {
      self.program = program.first
    } else {
      self.programArguments = program
    }
    
  }
  
  public convenience init(label: String, program: String...) {
    self.init(label: label, program: program)
  }
  
  public enum CodingKeys: String, CodingKey {
    case label = "Label"
    case disabled = "Disabled"
    case program = "Program"
    case programArguments = "ProgramArguments"
    
    // Program
    case workingDirectory = "WorkingDirectory"
    case standardOutPath = "StandardOutPath"
    case standardErrorPath = "StandardErrorPath"
    case environmentVariables = "EnvironmentVariables"
    
    // Run Conditions
    case runAtLoad = "RunAtLoad"
    case startInterval = "StartInterval"
    case onDemand = "OnDemand"
    case keepAlive = "KeepAlive"
    case watchPaths = "WatchPaths"
    
    // Security
    case umask = "Umask"
    case groupName = "GroupName"
    case userName = "UserName"
    case rootDirectory = "RootDirectory"
    
    // Run Constriants
    case launchOnlyOnce = "LaunchOnlyOnce"
    case limitLoadToSessionType = "LimitLoadToSessionType"
    
    // Process type
    case processType = "ProcessType"
    
  }
  
  
}
