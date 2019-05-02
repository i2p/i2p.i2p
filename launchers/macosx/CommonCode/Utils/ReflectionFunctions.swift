//
//  ReflectionFunctions.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 17/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class ReflectionFunctions {
  
  /// Given pointer to first element of a C array, invoke a function for each element
  func enumerateCArray<T>(array: UnsafePointer<T>, count: UInt32, f: (UInt32, T) -> ()) {
    var ptr = array
    for i in 0..<count {
      f(i, ptr.pointee)
      ptr = ptr.successor()
    }
  }
  
  /// Return name for a method
  func methodName(m: Method) -> String? {
    let sel = method_getName(m)
    let nameCString = sel_getName(sel)
    return String(cString: nameCString)
  }
  
  /// Print the names for each method in a class
  func printMethodNamesForClass(cls: AnyClass) {
    var methodCount: UInt32 = 0
    let methodList = class_copyMethodList(cls, &methodCount)
    if methodList != nil && methodCount > 0 {
      enumerateCArray(array: methodList!, count: methodCount) { i, m in
        let name = methodName(m: m) ?? "unknown"
        print("#\(i): \(name)")
      }
      
      free(methodList)
    }
  }
  
  /// Print the names for each method in a class with a specified name
  func printMethodNamesForClassNamed(classname: String) {
    // NSClassFromString() is declared to return AnyClass!, but should be AnyClass?
    let maybeClass: AnyClass? = NSClassFromString(classname)
    if let cls: AnyClass = maybeClass {
      printMethodNamesForClass(cls: cls)
    }
    else {
      print("\(classname): no such class")
    }
  }
}



