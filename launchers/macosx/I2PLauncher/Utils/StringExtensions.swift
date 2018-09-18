//
//  StringExtensions.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

extension String {
  
  func replace(target: String, withString: String) -> String
  {
    return self.replacingOccurrences(of: target, with: withString, options: NSString.CompareOptions.literal, range: nil)
  }
  
  /// Returns an array of string obtained splitting self at each newline ("\n").
  /// If the last character is a newline, it will be ignored (no empty string
  /// will be appended at the end of the array)
  public func splitByNewline() -> [String] {
    return self.split { $0 == "\n" }.map(String.init)
  }
  
  /// Returns an array of string obtained splitting self at each space, newline or TAB character
  public func splitByWhitespace() -> [String] {
    let whitespaces = Set<Character>([" ", "\n", "\t"])
    return self.split { whitespaces.contains($0) }.map(String.init)
  }
  
  public func splitByColon() -> [String] {
    return self.split { $0 == ":" }.map(String.init)
  }
}

