//
//  PreferencesViewController+TableView.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 08/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Cocoa

extension PreferencesViewController: NSTableViewDataSource {
  
  func numberOfRows(in tableView: NSTableView) -> Int {
    return Preferences.shared().count
  }
  
}

extension PreferencesViewController: NSTableViewDelegate {
  
  fileprivate enum CellIdentifiers {
    static let NameCell = "KeyColumnID"
    static let DefaultCell = "DefaultColumnID"
    static let ValueCell = "ValueColumnID"
  }
  
  func tableViewDoubleClick(_ sender:AnyObject) {
    
    // 1
    /*guard tableView.selectedRow >= 0,
     let item = Preferences.shared()[tableView.selectedRow] else {
     return
     }
     
     if item.isFolder {
     // 2
     self.representedObject = item.url as Any
     }
     else {
     // 3
     NSWorkspace.shared().open(item.url as URL)
     }
     */
  }
  
  func tableView(_ tableView: NSTableView, sortDescriptorsDidChange oldDescriptors: [NSSortDescriptor]) {
    // 1
    guard let sortDescriptor = tableView.sortDescriptors.first else {
      return
    }
    /*if let order = Directory.FileOrder(rawValue: sortDescriptor.key!) {
     // 2
     sortOrder = order
     sortAscending = sortDescriptor.ascending
     reloadFileList()
     }*/
  }
  
  
  func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
    
    //var image: NSImage?
    var text: String = ""
    var cellIdentifier: String = ""
    
    
    // 1
    guard let item = Preferences.shared()[row] else {
      return nil
    }
    
    // 2
    if tableColumn == tableView.tableColumns[0] {
      text = item.name!
      cellIdentifier = CellIdentifiers.NameCell
    } else if tableColumn == tableView.tableColumns[1] {
      text = "\(item.defaultValue!)"
      cellIdentifier = CellIdentifiers.DefaultCell
    } else if tableColumn == tableView.tableColumns[2] {
      let thing = (item.selectedValue ?? "none")
      text = "\(thing)"
      cellIdentifier = CellIdentifiers.ValueCell
    }
    
    // 3
    if let cell = tableView.make(withIdentifier: cellIdentifier, owner: nil) as? NSTableCellView {
      cell.textField?.stringValue = text
      //cell.imageView?.image = image ?? nil
      return cell
    }
    return nil
  }
  
}
