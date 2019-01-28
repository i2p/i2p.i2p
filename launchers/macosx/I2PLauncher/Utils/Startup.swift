//
//  Startup.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 22/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

class Startup {
  
  
  /*
  func applicationIsInStartUpItems() -> Bool {
    return itemReferencesInLoginItems().existingReference != nil
  }
  
  func toggleLaunchAtStartup() {
    let itemReferences = itemReferencesInLoginItems()
    let shouldBeToggled = (itemReferences.existingReference == nil)
    let loginItemsRef = LSSharedFileListCreate(
      nil,
      kLSSharedFileListSessionLoginItems.takeRetainedValue(),
      nil
      ).takeRetainedValue() as LSSharedFileList?
    
    if loginItemsRef != nil {
      if shouldBeToggled {
        if let appUrl: CFURL = NSURL.fileURLWithPath(Bundle.mainBundle().bundlePath) {
          LSSharedFileListInsertItemURL(loginItemsRef, itemReferences.lastReference, nil, nil, appUrl, nil, nil)
          print("Application was added to login items")
        }
      } else {
        if let itemRef = itemReferences.existingReference {
          LSSharedFileListItemRemove(loginItemsRef,itemRef);
          print("Application was removed from login items")
        }
      }
    }
  }
  
  func itemReferencesInLoginItems() -> (existingReference: LSSharedFileListItem?, lastReference: LSSharedFileListItem?) {
    var itemUrl = UnsafeMutablePointer<Unmanaged<CFURL>?>.allocate(capacity: 1)
    
    let appUrl = NSURL.fileURL(withPath: Bundle.main.bundlePath)
    if !appUrl.absoluteString.isEmpty {
      let loginItemsRef = LSSharedFileListCreate(
        nil,
        kLSSharedFileListSessionLoginItems.takeRetainedValue(),
        nil
        ).takeRetainedValue() as LSSharedFileList?
      
      if loginItemsRef != nil {
        let loginItems = LSSharedFileListCopySnapshot(loginItemsRef, nil).takeRetainedValue() as NSArray
        print("There are \(loginItems.count) login items")
        
        if(loginItems.count > 0) {
          let lastItemRef = loginItems.lastObject as! LSSharedFileListItem
          
          for var currentItem in loginItems {
            let currentItemRef = currentItem as! LSSharedFileListItem
            
            let urlRef: CFURL = LSSharedFileListItemCopyResolvedURL(currentItemRef, 0, nil) as! CFURL
            let url = urlRef.takeUnretainedValue()
            if !urlRef?.isEmpty {
              print("URL Ref: \(urlRef.lastPathComponent)")
              if urlRef.isEqual(appUrl) {
                return (currentItemRef, lastItemRef)
              }
            }
            else {
              print("Unknown login application")
            }
          }
          // The application was not found in the startup list
          return (nil, lastItemRef)
          
        } else  {
          let addatstart: LSSharedFileListItem = kLSSharedFileListItemBeforeFirst.takeRetainedValue()
          return(nil,addatstart)
        }
      }
    }
    
    return (nil, nil)
  }*/
}
