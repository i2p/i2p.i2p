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
  
  enum ShowAsMode {
    case bothIcon
    case menubarIcon
    case dockIcon
  }
  
  private var prefObject: Dictionary<String,Any> = Dictionary<String,Any>()
  private var prefDict = Dictionary<String,PreferenceRow>()
  private var prefDefaultDict: Dictionary<String,Any> = Dictionary<String,Any>()
  
  var notifyOnStatusChange: Bool {
    get { return UserDefaults.standard.bool(forKey: "notifyOnStatusChange") }
    set { UserDefaults.standard.set(newValue, forKey: "notifyOnStatusChange") }
  }
  
  var count: Int = 0
  
  // Interface with a string setting in background
  var showAsIconMode: ShowAsMode {
    get {
      var mode = self["I2Pref_showAsIconMode"]
      if (mode == nil) {
        mode = "bothIcon"
      }
      switch (mode as! String) {
      case "bothIcon":
        return ShowAsMode.bothIcon
      case "dockIcon":
        return ShowAsMode.dockIcon
      case "menubarIcon":
        return ShowAsMode.menubarIcon
      default:
        return ShowAsMode.bothIcon
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
      self["I2Pref_showAsIconMode"] = newMode
      self.syncPref()
    }
  }

  // Lookup by name
  subscript(prefName:String) -> Any? {
    get  {
      let ret = prefObject[prefName]
      if (ret != nil) {
        return ret
      }
      return prefDefaultDict[prefName]
    }
    set(newValue) {
      prefObject[prefName] = newValue
      prefDict[prefName] = PreferenceRow(prefName, newValue, prefDefaultDict[prefName])
      UserDefaults.standard.set(newValue, forKey: prefName)
      self.syncPref()
    }
  }
  
  func syncPref() {
    UserDefaults.standard.setPersistentDomain(self.prefObject, forName: Identifiers.applicationDomainId)
    UserDefaults.standard.synchronize()
  }
  
  // Lookup by index
  subscript(index:Int) -> PreferenceRow? {
    get  {
      return prefDict[Array(prefDict.keys)[index]]
    }
    set(newValue) {
      let pKey = Array(prefDefaultDict.keys)[index]
      prefDict[pKey] = newValue!
      prefObject[pKey] = newValue!.asRawValue()
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
    defaults["I2Pref_useServiceManagementAsStartupTool"] = false
    defaults["I2Pref_firefoxProfilePath"] = NSString(format: "%@/Library/Application Support/i2p/profile", home)
    defaults["I2Pref_consolePortCheckNum"] = 7657
    defaults["I2Pref_i2pBaseDirectory"] = NSString(format: "%@/Library/I2P", home)
    defaults["I2Pref_i2pLogDirectory"] = NSString(format: "%@/Library/Logs/I2P", home)
    defaults["I2Pref_showAsIconMode"] = "bothIcon"
    defaults["I2Pref_javaCommandPath"] = "/usr/libexec/java_home -v 1.7+ --exec java "
    defaults["I2Pref_javaCommandOptions"] = "-Xmx512M -Xms128m"
    defaults["I2Pref_featureToggleExperimental"] = false
    preferences.prefDefaultDict = defaults
    
    /*if (preferences.prefDict.isEmpty) {
      print("Stored new user defaults")
      preferences.addDictToPrefTable(defaults)
    }*/
    for name in Array(preferences.prefDefaultDict.keys) {
      let potentialValue = UserDefaults.standard.object(forKey: name)
      //preferences.prefDict[name] = PreferenceRow(name, potentialValue, preferences.prefDefaultDict[name])
      preferences[name] = potentialValue
    }
    preferences.count = preferences.prefDict.keys.count
    UserDefaults.standard.register(defaults: defaults)
    UserDefaults.standard.setPersistentDomain(preferences.prefObject, forName: Identifiers.applicationDomainId)
    UserDefaults.standard.synchronize()
    
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
        self[pKey] = pVal
      } else {
        print("Skipping preference -> \(pKey)")
      }
    }
  }
  
  // Initialization
  
  private override init() {
    super.init()
    let fromDisk = UserDefaults.standard.persistentDomain(forName: Identifiers.applicationDomainId) ?? Dictionary<String,Any>()
    for (pKey, pVal) in fromDisk {
      if (pKey.starts(with: "I2P")) {
        print("Preference -> \(pKey)")
        self[pKey] = pVal
      } else {
        print("Skipping preference -> \(pKey)")
      }
    }
    print("Preferences size from disk is: \(prefObject.count).")
    self.syncPref()
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
    self.addDictToPrefTable(self.prefObject, false)
  }
  
  
  // MARK: - Accessors for Application Preferences
  
  var startRouterOnLauncherStart: Bool {
    get {
      let dfl = self.prefDefaultDict["I2Pref_startRouterAtStartup"] as! Bool
      return (self["I2Pref_startRouterAtStartup"] as? Bool ?? dfl)
    }
    set(newValue) {
      self["I2Pref_startRouterAtStartup"] = newValue
      self.syncPref()
    }
  }
  
  var stopRouterOnLauncherShutdown: Bool {
    get {
      let dfl = self.prefDefaultDict["I2Pref_stopRouterAtShutdown"] as! Bool
      return (self["I2Pref_stopRouterAtShutdown"] as? Bool ?? dfl)
    }
    set(newValue) {
      self["I2Pref_stopRouterAtShutdown"] = newValue
      self.syncPref()
    }
  }
  
  var allowAdvancedPreferenceEdit: Bool {
    get {
      let dfl = self.prefDefaultDict["I2Pref_allowAdvancedPreferences"] as! Bool
      return (self["I2Pref_allowAdvancedPreferences"] as? Bool ?? dfl)
    }
    set(newValue) {
      self["I2Pref_allowAdvancedPreferences"] = newValue
      self.syncPref()
    }
  }
  
  var alsoStartFirefoxOnLaunch: Bool {
    get {
      let dfl = self.prefDefaultDict["I2Pref_alsoStartFirefoxOnLaunch"] as! Bool
      return (self["I2Pref_alsoStartFirefoxOnLaunch"] as? Bool ?? dfl)
    }
    set(newValue) {
      self["I2Pref_alsoStartFirefoxOnLaunch"] = newValue
      self.syncPref()
    }
  }
  
  var featureToggleExperimental: Bool {
    get {
      let dfl = self.prefDefaultDict["I2Pref_featureToggleExperimental"] as! Bool
      return (self["I2Pref_featureToggleExperimental"] as? Bool ?? dfl)
    }
    set(newValue) {
      self["I2Pref_featureToggleExperimental"] = newValue
      self.syncPref()
    }
  }
  
  var i2pBaseDirectory: String {
    get {
      let dfl = self.prefDefaultDict["I2Pref_i2pBaseDirectory"] as! String
      return (self["I2Pref_i2pBaseDirectory"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid directory path, and that it exists.
      self["I2Pref_i2pBaseDirectory"] = newValue
      self.syncPref()
    }
  }
  
  var i2pLogDirectory: String {
    get {
      let dfl = self.prefDefaultDict["I2Pref_i2pLogDirectory"] as! String
      return (self["I2Pref_i2pLogDirectory"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid java command path, check if it executes with -version.
      self["I2Pref_i2pLogDirectory"] = newValue
      self.syncPref()
    }
  }
  
  var javaCommandPath: String {
    get {
      let dfl = self.prefDefaultDict["I2Pref_javaCommandPath"] as! String
      return (self["I2Pref_javaCommandPath"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid java command path, check if it executes with -version.
      self["I2Pref_javaCommandPath"] = newValue
      self.syncPref()
    }
  }
  
  var javaCommandOptions: String {
    get {
      let dfl = self.prefDefaultDict["I2Pref_javaCommandOptions"] as! String
      return (self["I2Pref_javaCommandOptions"] as? String ?? dfl)
    }
    set(newValue) {
      // TODO: Check if string is a valid set of java options
      self["I2Pref_javaCommandOptions"] = newValue
      self.syncPref()
    }
  }
  
  
}

