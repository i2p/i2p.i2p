//
//  EmbeddedConsoleView.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 08/12/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import AppKit
import WebKit

/*
protocol EConsoleViewWrapper {}

class WebViewSource {
  class func webView() -> EConsoleViewWrapper {
    if #available(OSX 10.12, *) {
      //
      return EmbeddedConsoleView(coder: NSCoder())!
    } else {
      // Sorry
      return EmbeddedConsoleViewDummy()
    }
  }
}

extension EConsoleViewWrapper {
  static func instantiate(frame frameRect: NSRect) -> EConsoleViewWrapper {
    return WebViewSource.webView()
  }
}
*/

class ConsoleWindowController: NSWindowController {
  override func windowDidLoad() {
    super.windowDidLoad()
/*    let v: NSView = WebViewSource.webView() as! NSView
    v.wantsLayer = true
    self.window?.contentView?.addSubview(v)*/
  }
}

class ConsoleViewController: NSViewController {
  var webView: WKWebView!
  let consoleWebUrl = URL(string: "http://127.0.0.1:7657")
  
  override func loadView() {
    let webConfiguration = WKWebViewConfiguration()
    webView = WKWebView(frame: .zero, configuration: webConfiguration)
    //webView.uiDelegate = self
    view = webView
  }
  override func viewDidLoad() {
    super.viewDidLoad()
    
    webView.load(URLRequest(url: consoleWebUrl!))
  }

}

/*
@available(OSX 10.12, *)
class EmbeddedConsoleView: WKWebView, EConsoleViewWrapper {
  
  let consoleWebUrl = URL(string: "http://127.0.0.1:7657")
  
  func setupWebViewForConsole(_ f: NSRect = NSRect(x: 0, y: 0, width: 800, height: 400)) {
    self.allowsBackForwardNavigationGestures = true
    self.configuration.preferences.javaScriptEnabled = true
    self.configuration.preferences.plugInsEnabled = false
    
    self.load(URLRequest(url: consoleWebUrl!))
  }
  
  override func viewWillDraw() {
    super.viewWillDraw()
  }
  
  required init?(coder decoder: NSCoder) {
    super.init(coder: decoder)
    self.setupWebViewForConsole()
  }
  
}

class EmbeddedConsoleViewDummy: NSView, EConsoleViewWrapper {}
*/

