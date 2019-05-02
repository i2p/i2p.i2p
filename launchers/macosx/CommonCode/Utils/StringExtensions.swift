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
  
  func leftPadding(toLength: Int, withPad character: Character) -> String {
    let stringLength = self.count
    if stringLength < toLength {
      return String(repeatElement(character, count: toLength - stringLength)) + self
    } else {
      return String(self.suffix(toLength))
    }
  }
}


extension String {
  subscript (i: Int) -> Character {
    return self[index(startIndex, offsetBy: i)]
  }
  subscript (bounds: CountableRange<Int>) -> Substring {
    let start = index(startIndex, offsetBy: bounds.lowerBound)
    let end = index(startIndex, offsetBy: bounds.upperBound)
    return self[start ..< end]
  }
  subscript (bounds: CountableClosedRange<Int>) -> Substring {
    let start = index(startIndex, offsetBy: bounds.lowerBound)
    let end = index(startIndex, offsetBy: bounds.upperBound)
    return self[start ... end]
  }
  subscript (bounds: CountablePartialRangeFrom<Int>) -> Substring {
    let start = index(startIndex, offsetBy: bounds.lowerBound)
    let end = index(endIndex, offsetBy: -1)
    return self[start ... end]
  }
  subscript (bounds: PartialRangeThrough<Int>) -> Substring {
    let end = index(startIndex, offsetBy: bounds.upperBound)
    return self[startIndex ... end]
  }
  subscript (bounds: PartialRangeUpTo<Int>) -> Substring {
    let end = index(startIndex, offsetBy: bounds.upperBound)
    return self[startIndex ..< end]
  }
}

/*
 * This is functions for comparing version numbers.
 * Example usage:
 *   "3.0.0" >= "3.0.0.1" // false
 *   "3.0.0" > "3.0.0.1" // false
 *   "3.0.0" <= "3.0.0.1" // true
 *   "3.0.0.1" >= "3.0.0.1" // true
 */
extension String {
  
  static func ==(lhs: String, rhs: String) -> Bool {
    return lhs.compare(rhs, options: .numeric) == .orderedSame
  }
  
  static func <(lhs: String, rhs: String) -> Bool {
    return lhs.compare(rhs, options: .numeric) == .orderedAscending
  }
  
  static func <=(lhs: String, rhs: String) -> Bool {
    return lhs.compare(rhs, options: .numeric) == .orderedAscending || lhs.compare(rhs, options: .numeric) == .orderedSame
  }
  
  static func >(lhs: String, rhs: String) -> Bool {
    return lhs.compare(rhs, options: .numeric) == .orderedDescending
  }
  
  static func >=(lhs: String, rhs: String) -> Bool {
    return lhs.compare(rhs, options: .numeric) == .orderedDescending || lhs.compare(rhs, options: .numeric) == .orderedSame
  }
  
}

