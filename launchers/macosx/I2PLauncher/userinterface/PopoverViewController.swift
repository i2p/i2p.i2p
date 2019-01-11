//
//  PopoverViewController.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Cocoa

class PopoverViewController: NSViewController {
  
  @IBOutlet var routerStatusViewOutlet: RouterStatusView?
  
  func getRouterStatusView() -> RouterStatusView? {
    return self.routerStatusViewOutlet
  }
  
  required init?(coder: NSCoder) {
    super.init(coder: coder)
  }
  
  @IBAction func onPreferencesClick(_ sender: Any) {
    StatusBarController.launchPreferences(sender)
  }
  
  override func viewDidLoad() {
    super.viewDidLoad()
    // Do view setup here.
  }
}


extension PopoverViewController {
  static func freshController() -> PopoverViewController {
    let storyboard = NSStoryboard(name: "Storyboard", bundle: Bundle.main)
    //2.
    let identifier = NSStoryboard.SceneIdentifier(stringLiteral: "PopoverView")
    //3.
    guard let viewcontroller = storyboard.instantiateController(withIdentifier: identifier as String) as? PopoverViewController else {
      fatalError("Why cant i find PopoverViewController? - Check PopoverViewController.storyboard")
    }
    //let viewcontroller = PopoverViewController()
    return viewcontroller
  }
}

