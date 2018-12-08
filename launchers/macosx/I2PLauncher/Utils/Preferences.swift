//
//  Preferences.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 01/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class PreferenceRow {
  var name: String?
  var defaultValue: Any?
  var selectedValue: Any?
  
  init(_ name: String, _ value: Any?, _ defaultVal: Any? = "") {
    self.name = name
    self.selectedValue = value
    self.defaultValue = defaultVal
  }
  
  func asRawValue() -> Any {
    return self.selectedValue ?? self.defaultValue!
  }
}

class Preferences  : NSObject {
  private var prefObject: Dictionary<String,Any> = Dictionary<String,Any>()
  private var prefDict = Dictionary<String,PreferenceRow>()
  private var prefDefaultDict: Dictionary<String,Any>?
  
  // This makes an read-only property computed from another property
  // It's usage is mainly in UI Table view, so we want the prefDict size
  var count: Int {
    get {
      return prefDict.count
    }
  }
  
  // Interface with a string setting in background
  var showAsIconMode: PreferencesViewController.ShowAsMode {
    get {
      var mode = self.prefObject["I2Pref_showAsIconMode"]
      if (mode == nil) {
        mode = "bothIcon"
      }
      switch (mode as! String) {
      case "bothIcon":
        return PreferencesViewController.ShowAsMode.bothIcon
      case "dockIcon":
        return PreferencesViewController.ShowAsMode.dockIcon
      case "menubarIcon":
        return PreferencesViewController.ShowAsMode.menubarIcon
      default:
        return PreferencesViewController.ShowAsMode.bothIcon
      }
    }
    set(newVal) {
      //
      var newMode: String = "bothIcon"
      switch newVal {
      case .bothIcon:
        newMode = "bothIcon"
      case .menubarIcon:
        newMode = "menubarIcon"
      case .dockIcon:
        newMode = "dockIcon"
      }
      self.prefObject["I2Pref_showAsIconMode"] = newMode
      UserDefaults.standard.setPersistentDomain(self.prefObject, forName: APPDOMAIN)
    }
  }

  // Lookup by name
  subscript(prefName:String) -> Any? {
    get  {
      return prefObject[prefName]
    }
    set(newValue) {
      prefObject[prefName] = newValue
      prefDict[prefName] = PreferenceRow(prefName, newValue)
      UserDefaults.standard.setPersistentDomain(self.prefObject, forName: APPDOMAIN)
    }
  }
  
  // Lookup by index
  subscript(index:Int) -> PreferenceRow? {
    get  {
      return prefDict[Array(prefDict.keys)[index]]
    }
    set(newValue) {
      let pKey = Array(prefDict.keys)[index]
      prefDict[pKey] = newValue!
      prefObject[pKey] = newValue!.asRawValue()
      UserDefaults.standard.setPersistentDomain(self.prefObject, forName: APPDOMAIN)
    }
  }
  
  private static var sharedPreferences: Preferences = {
    let preferences = Preferences()
    
    // Setup defaults
    
    var home = NSHomeDirectory()
    // Add default values
    var defaults = Dictionary<String,Any>()
    defaults["I2Pref_enableLogging"] = true
    defaults["I2Pref_enableVerboseLogging"] = true
    defaults["I2Pref_autoStartRouterAtBoot"] = false
    defaults["I2Pref_startLauncherAtLogin"] = false
    defaults["I2Pref_startRouterAtStartup"] = true
    defaults["I2Pref_stopRouterAtShutdown"] = true
    defaults["I2Pref_letRouterLiveEvenLauncherDied"] = false
    defaults["I2Pref_allowAdvancedPreferences"] = false
    defaults["I2Pref_alsoStartFirefoxOnLaunch"] = true
    defaults["I2Pref_firefoxBundlePath"] = "/Applications/Firefox.app"
    defaults["I2Pref_consolePortCheckNum"] = 7657
    defaults["I2Pref_i2pBaseDirectory"] = NSString(format: "%@/Library/I2P", home)
    defaults["I2Pref_i2pLogDirectory"] = NSString(format: "%@/Library/Logs/I2P", home)
    defaults["I2Pref_showAsIconMode"] = "bothIcon"
    defaults["I2Pref_javaCommandPath"] = "/usr/libexec/java_home -v 1.7+ --exec java "
    defaults["I2Pref_javaCommandOptions"] = "-Xmx512M -Xms128m"
    defaults["I2Pref_featureToggleExperimental"] = false
    preferences.prefDefaultDict = defaults
    
    if (preferences.prefDict.isEmpty) {
      print("Stored new user defaults")
      preferences.addDictToPrefTable(defaults)
    }
    
    print("User Preferences loaded - Got \(preferences.count) items.")
    
    return preferences
  }()
  
  // MARK: -
  
  func addDictToPrefTable(_ dict: Dictionary<String,Any>, _ emptyFirst: Bool = true) {
    if (emptyFirst) {
      self.prefDict.removeAll()
    }
    for (pKey, pVal) in dict {
      if (pKey.starts(with: "I2P")) {
        print("Preference -> \(pKey)")
        prefDict[pKey] = PreferenceRow(pKey, pVal, self.prefDefaultDict![pKey])
      } else {
        print("Skipping preference -> \(pKey)")
      }
    }
  }
  
  // Initialization
  
  private override init() {
    super.init()
    self.prefObject = UserDefaults.standard.persistentDomain(forName: APPDOMAIN) ?? Dictionary<String,Any>()
    print("Preferences size from disk is: \(prefObject.count).")
    self.addDictToPrefTable(self.prefObject)
    UserDefaults.standard.setPersistentDomain(self.prefObject, forName: APPDOMAIN)
  }
  
  // TODO: Make menubar icon optional
  func getMenubarIconStateIsShowing() -> Bool {
    return true
  }
  
  // MARK: - Accessors
  
  class func shared() -> Preferences {
    return sharedPreferences
  }
  
  func redrawPrefTableItems() {
    self.addDictToPrefTable(self.prefObject, true)
  }
  
  
  // MARK: - Accessors for Application Preferences
  
  var startRouterOnLauncherStart: Bool {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_startRouterAtStartup"] as! Bool
      return (self.prefObject["I2Pref_startRouterAtStartup"] as? Bool ?? dfl)
    }
    set(newValue) {
      self.prefObject["I2Pref_startRouterAtStartup"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var stopRouterOnLauncherShutdown: Bool {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_stopRouterAtShutdown"] as! Bool
      return (self.prefObject["I2Pref_stopRouterAtShutdown"] as? Bool ?? dfl)
    }
    set(newValue) {
      self.prefObject["I2Pref_stopRouterAtShutdown"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var allowAdvancedPreferenceEdit: Bool {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_allowAdvancedPreferences"] as! Bool
      return (self.prefObject["I2Pref_allowAdvancedPreferences"] as? Bool ?? dfl)
    }
    set(newValue) {
      self.prefObject["I2Pref_allowAdvancedPreferences"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var alsoStartFirefoxOnLaunch: Bool {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_alsoStartFirefoxOnLaunch"] as! Bool
      return (self.prefObject["I2Pref_alsoStartFirefoxOnLaunch"] as? Bool ?? dfl)
    }
    set(newValue) {
      self.prefObject["I2Pref_alsoStartFirefoxOnLaunch"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var featureToggleExperimental: Bool {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_featureToggleExperimental"] as! Bool
      return (self.prefObject["I2Pref_featureToggleExperimental"] as? Bool ?? dfl)
    }
    set(newValue) {
      self.prefObject["I2Pref_featureToggleExperimental"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var i2pBaseDirectory: String {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_i2pBaseDirectory"] as! String
      return (self.prefObject["I2Pref_i2pBaseDirectory"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid directory path, and that it exists.
      self.prefObject["I2Pref_i2pBaseDirectory"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var i2pLogDirectory: String {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_i2pLogDirectory"] as! String
      return (self.prefObject["I2Pref_i2pLogDirectory"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid java command path, check if it executes with -version.
      self.prefObject["I2Pref_i2pLogDirectory"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var javaCommandPath: String {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_javaCommandPath"] as! String
      return (self.prefObject["I2Pref_javaCommandPath"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid java command path, check if it executes with -version.
      self.prefObject["I2Pref_javaCommandPath"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  var javaCommandOptions: String {
    get {
      let dfl = self.prefDefaultDict?["I2Pref_javaCommandOptions"] as! String
      return (self.prefObject["I2Pref_javaCommandOptions"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid set of java options
      self.prefObject["I2Pref_javaCommandOptions"] = newValue
      UserDefaults.standard.synchronize()
    }
  }
  
  
}

