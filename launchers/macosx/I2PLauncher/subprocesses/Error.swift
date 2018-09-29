//
//  Error.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

public class Error {
  
  /// Prints to console the arguments and exits with status 1
  static func die(arguments: Any...) -> Never  {
    let output = "ERROR: " + arguments.reduce("") { $0 + "\($1) " }
    let trimOutput = output.trimmingCharacters(in: CharacterSet.whitespaces) + "\n"
    let stderr = FileHandle.standardError
    stderr.write(trimOutput.data(using: String.Encoding.utf8)!)
    exit(1)
  }
}

