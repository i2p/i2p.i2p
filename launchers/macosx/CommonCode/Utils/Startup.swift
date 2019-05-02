//
//  Startup.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 22/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation


class Startup : NSObject {
  
  let loginItemsList : LSSharedFileList = LSSharedFileListCreate(nil, kLSSharedFileListSessionLoginItems.takeRetainedValue(), nil).takeRetainedValue();
  
  
  
  func addLoginItem(_ path: CFURL) -> Bool {
    
    if(getLoginItem(path) != nil) {
      print("Login Item has already been added to the list.");
      return true;
    }
    
    let path : CFURL = CFURLCreateWithString(nil, Bundle.main.bundleURL.absoluteString as CFString, nil);
    print("Path adding to Login Item list is: ", path);
    
    // add new Login Item at the end of Login Items list
    if let loginItem = LSSharedFileListInsertItemURL(loginItemsList,
                                                     getLastLoginItemInList(),
                                                     nil, nil,
                                                     path,
                                                     nil, nil) {
      print("Added login item is: ", loginItem);
      return true;
    }
    
    return false;
  }
  
  
  func removeLoginItem(_ path: CFURL) -> Bool {
    
    // remove Login Item from the Login Items list
    if let oldLoginItem = getLoginItem(path) {
      print("Old login item is: ", oldLoginItem);
      if(LSSharedFileListItemRemove(loginItemsList, oldLoginItem) == noErr) {
        return true;
      }
      return false;
    }
    print("Login Item for given path not found in the list.");
    return true;
  }
  
  
  func getLoginItem(_ path : CFURL) -> LSSharedFileListItem! {
    
    let path : CFURL = CFURLCreateWithString(nil, Bundle.main.bundleURL.absoluteString as CFString, nil);
    
    
    // Copy all login items in the list
    let loginItems : NSArray = LSSharedFileListCopySnapshot(loginItemsList, nil).takeRetainedValue();
    
    var foundLoginItem : LSSharedFileListItem?;
    var nextItemUrl : Unmanaged<CFURL>?;
    
    // Iterate through login items to find one for given path
    print("App URL: ", path);
    for var i in (0..<loginItems.count)  // CFArrayGetCount(loginItems)
    {
      
      let nextLoginItem : LSSharedFileListItem = loginItems.object(at: i) as! LSSharedFileListItem; // CFArrayGetValueAtIndex(loginItems, i).;
      
      
      if(LSSharedFileListItemResolve(nextLoginItem, 0, &nextItemUrl, nil) == noErr) {
        
        
        
        print("Next login item URL: ", nextItemUrl!.takeUnretainedValue());
        // compare searched item URL passed in argument with next item URL
        if(nextItemUrl!.takeRetainedValue() == path) {
          foundLoginItem = nextLoginItem;
        }
      }
    }
    
    return foundLoginItem;
  }
  
  func getLastLoginItemInList() -> LSSharedFileListItem! {
    
    // Copy all login items in the list
    let loginItems : NSArray = LSSharedFileListCopySnapshot(loginItemsList, nil).takeRetainedValue() as NSArray;
    if(loginItems.count > 0) {
      let lastLoginItem = loginItems.lastObject as! LSSharedFileListItem;
      
      print("Last login item is: ", lastLoginItem);
      return lastLoginItem
    }
    
    return kLSSharedFileListItemBeforeFirst.takeRetainedValue();
  }
  
  func isLoginItemInList(_ path : CFURL) -> Bool {
    
    if(getLoginItem(path) != nil) {
      return true;
    }
    
    return false;
  }
  
  static func appPath() -> CFURL {
    
    return NSURL.fileURL(withPath: Bundle.main.bundlePath) as CFURL;
  }
  
}
