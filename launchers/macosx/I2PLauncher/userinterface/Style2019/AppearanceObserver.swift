//
//  AppearanceObserver.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 15/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Cocoa

protocol AppearanceObserver: AnyObject {
  func changeAppearance(to newAppearance: NSAppearance)
}

@available(OSX 10.14, *)
class Appearance {
  class Weak {
    fileprivate weak var object: AnyObject?
    
    init(_ object: AnyObject) {
      self.object = object
    }
  }
  
  private static var effectiveAppearanceObserver: Any? = {
    return NSApplication.shared.observe(
      \NSApplication.effectiveAppearance,
      options: [.new, .initial]
    ) { _, change in
      guard let newValue = change.newValue else { return }
      
      Appearance.fire(newAppearance: newValue)
    }
  }()
  
  private static var observers = [Weak]()
  
  private static func fire(newAppearance: NSAppearance) {
    observers = observers.filter {
      guard let object = $0.object else { return false }
      
      (object as? AppearanceObserver)?.changeAppearance(to: newAppearance)
      return true
    }
  }
  
  static func addObserver(_ observer: AppearanceObserver) {
    observers = observers.filter { $0.object != nil }
    observers.append(Weak(observer))
    
    if effectiveAppearanceObserver == nil {
      fatalError("Did not setup appearance observer.")
    }
  }
  
  static func removeObserver(_ observer: AppearanceObserver) {
    observers = observers.filter { $0.object != nil && $0.object !== observer }
  }
}

enum InterfaceStyle : String {
  case Dark, Light
  
  init() {
    let type = UserDefaults.standard.string(forKey: "AppleInterfaceStyle") ?? "Light"
    self = InterfaceStyle(rawValue: type)!
  }
}


extension NSAppearance {
  var isDarkMode: Bool {
    let currentStyle = InterfaceStyle()
    if #available(OSX 10.14, *) {
      return currentStyle == .Dark
    } else {
      return false
    }
  }
}



