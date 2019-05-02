//
//  Icons.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 11/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Cocoa

class CheckmarkIcon: NSView {
  var color: NSColor = NSColor(calibratedRed: 0.46, green: 0.78, blue: 0.56, alpha: 1) {
    didSet {
      self.needsDisplay = true
    }
  }
  
  override func draw(_ dirtyRect: NSRect) {
    super.draw(dirtyRect)
    
    color.setStroke()
    
    let checkmarkPath = NSBezierPath()
    checkmarkPath.lineWidth = 3
    checkmarkPath.move(to: NSPoint(x: 17.01, y: 9.15))
    checkmarkPath.curve(to: NSPoint(x: 16.3, y: 9.45),
                        controlPoint1: NSPoint(x: 16.75, y: 9.15),
                        controlPoint2: NSPoint(x: 16.5, y: 9.25))
    checkmarkPath.line(to: NSPoint(x: 2.3, y: 23.45))
    checkmarkPath.line(to: NSPoint(x: 3.71, y: 24.86))
    checkmarkPath.line(to: NSPoint(x: 17.01, y: 11.57))
    checkmarkPath.line(to: NSPoint(x: 42.3, y: 36.86))
    checkmarkPath.line(to: NSPoint(x: 43.71, y: 35.45))
    checkmarkPath.line(to: NSPoint(x: 17.71, y: 9.45))
    checkmarkPath.curve(to: NSPoint(x: 17.01, y: 9.15),
                        controlPoint1: NSPoint(x: 17.52, y: 9.25),
                        controlPoint2: NSPoint(x: 17.26, y: 9.15))
    checkmarkPath.close()
    checkmarkPath.stroke()
  }
}

class CrossIcon: NSView {
  var color: NSColor = NSColor(calibratedRed: 0.9, green: 0.78, blue: 0.56, alpha: 1) {
    didSet {
      self.needsDisplay = true
    }
  }
  
  override func draw(_ dirtyRect: NSRect) {
    super.draw(dirtyRect)
    
    color.setFill()
    
    let context = NSGraphicsContext.current!.cgContext
    
    NSGraphicsContext.saveGraphicsState()
    context.translateBy(x: 23, y: 23)
    context.rotate(by: -45 * CGFloat.pi / 180)
    NSBezierPath(rect: NSRect(x: -26.88, y: -1, width: 53.75, height: 4)).fill()
    NSGraphicsContext.restoreGraphicsState()
    
    NSGraphicsContext.saveGraphicsState()
    context.translateBy(x: 23, y: 23)
    context.rotate(by: -45 * CGFloat.pi / 180)
    NSBezierPath(rect: NSRect(x: -1, y: -26.88, width: 4, height: 53.75)).fill()
    NSGraphicsContext.restoreGraphicsState()
  }
}

class RefreshIcon: NSView {
  var color = NSColor.secondaryLabelColor {
    didSet {
      self.needsDisplay = true
    }
  }
  
  override func draw(_ dirtyRect: NSRect) {
    super.draw(dirtyRect)
    
    color.setFill()
    
    let circle = NSBezierPath()
    circle.move(to: NSPoint(x: 23, y: 3))
    circle.line(to: NSPoint(x: 23, y: 5))
    circle.curve(to: NSPoint(x: 41, y: 23),
                 controlPoint1: NSPoint(x: 32.93, y: 5),
                 controlPoint2: NSPoint(x: 41, y: 13.07))
    circle.curve(to: NSPoint(x: 23, y: 41),
                 controlPoint1: NSPoint(x: 41, y: 32.93),
                 controlPoint2: NSPoint(x: 32.93, y: 41))
    circle.curve(to: NSPoint(x: 5, y: 23),
                 controlPoint1: NSPoint(x: 13.07, y: 41),
                 controlPoint2: NSPoint(x: 5, y: 32.93))
    circle.curve(to: NSPoint(x: 14.47, y: 7.14),
                 controlPoint1: NSPoint(x: 5, y: 16.37),
                 controlPoint2: NSPoint(x: 8.63, y: 10.29))
    circle.line(to: NSPoint(x: 13.53, y: 5.38))
    circle.curve(to: NSPoint(x: 3, y: 23),
                 controlPoint1: NSPoint(x: 7.03, y: 8.88),
                 controlPoint2: NSPoint(x: 3, y: 15.63))
    circle.curve(to: NSPoint(x: 23, y: 43),
                 controlPoint1: NSPoint(x: 3, y: 34.03),
                 controlPoint2: NSPoint(x: 11.97, y: 43))
    circle.curve(to: NSPoint(x: 43, y: 23),
                 controlPoint1: NSPoint(x: 34.03, y: 43),
                 controlPoint2: NSPoint(x: 43, y: 34.03))
    circle.curve(to: NSPoint(x: 23, y: 3),
                 controlPoint1: NSPoint(x: 43, y: 11.97),
                 controlPoint2: NSPoint(x: 34.03, y: 3))
    circle.close()
    circle.fill()
    
    let arrowHead = NSBezierPath()
    arrowHead.move(to: NSPoint(x: 4.2, y: 3.02))
    arrowHead.line(to: NSPoint(x: 3.8, y: 4.98))
    arrowHead.line(to: NSPoint(x: 13, y: 6.82))
    arrowHead.line(to: NSPoint(x: 13, y: 16))
    arrowHead.line(to: NSPoint(x: 15, y: 16))
    arrowHead.line(to: NSPoint(x: 15, y: 6))
    arrowHead.curve(to: NSPoint(x: 14.2, y: 5.02),
                    controlPoint1: NSPoint(x: 15, y: 5.52),
                    controlPoint2: NSPoint(x: 14.66, y: 5.11))
    arrowHead.line(to: NSPoint(x: 4.2, y: 3.02))
    arrowHead.close()
    arrowHead.fill()
  }
}

class GearIcon: NSView {
  var color = NSColor.secondaryLabelColor {
    didSet {
      self.needsDisplay = true
    }
  }
  
  override func draw(_ dirtyRect: NSRect) {
    super.draw(dirtyRect)
    
    color.setFill()
    
    let outerCog = NSBezierPath()
    outerCog.move(to: NSPoint(x: 26.76, y: 9.49))
    outerCog.curve(to: NSPoint(x: 27.62, y: 8.99),
                   controlPoint1: NSPoint(x: 27.11, y: 9.49),
                   controlPoint2: NSPoint(x: 27.44, y: 9.3))
    outerCog.line(to: NSPoint(x: 28.4, y: 7.64))
    outerCog.curve(to: NSPoint(x: 31.13, y: 6.9),
                   controlPoint1: NSPoint(x: 28.93, y: 6.72),
                   controlPoint2: NSPoint(x: 30.21, y: 6.37))
    outerCog.line(to: NSPoint(x: 32.87, y: 7.9))
    outerCog.curve(to: NSPoint(x: 33.6, y: 10.64),
                   controlPoint1: NSPoint(x: 33.82, y: 8.46),
                   controlPoint2: NSPoint(x: 34.15, y: 9.68))
    outerCog.line(to: NSPoint(x: 32.82, y: 11.98))
    outerCog.curve(to: NSPoint(x: 32.98, y: 13.18),
                   controlPoint1: NSPoint(x: 32.6, y: 12.37),
                   controlPoint2: NSPoint(x: 32.66, y: 12.86))
    outerCog.curve(to: NSPoint(x: 36.48, y: 19.26),
                   controlPoint1: NSPoint(x: 34.63, y: 14.87),
                   controlPoint2: NSPoint(x: 35.85, y: 16.97))
    outerCog.curve(to: NSPoint(x: 37.44, y: 19.99),
                   controlPoint1: NSPoint(x: 36.6, y: 19.69),
                   controlPoint2: NSPoint(x: 36.99, y: 19.99))
    outerCog.line(to: NSPoint(x: 39, y: 19.99))
    outerCog.curve(to: NSPoint(x: 41, y: 21.99),
                   controlPoint1: NSPoint(x: 40.1, y: 19.99),
                   controlPoint2: NSPoint(x: 41, y: 20.89))
    outerCog.line(to: NSPoint(x: 41, y: 23.99))
    outerCog.curve(to: NSPoint(x: 39, y: 25.99),
                   controlPoint1: NSPoint(x: 41, y: 25.1),
                   controlPoint2: NSPoint(x: 40.1, y: 25.99))
    outerCog.line(to: NSPoint(x: 37.44, y: 25.99))
    outerCog.curve(to: NSPoint(x: 36.48, y: 26.73),
                   controlPoint1: NSPoint(x: 36.99, y: 25.99),
                   controlPoint2: NSPoint(x: 36.6, y: 26.29))
    outerCog.curve(to: NSPoint(x: 32.98, y: 32.81),
                   controlPoint1: NSPoint(x: 35.85, y: 29.02),
                   controlPoint2: NSPoint(x: 34.63, y: 31.12))
    outerCog.curve(to: NSPoint(x: 32.82, y: 34.01),
                   controlPoint1: NSPoint(x: 32.66, y: 33.13),
                   controlPoint2: NSPoint(x: 32.6, y: 33.62))
    outerCog.line(to: NSPoint(x: 33.6, y: 35.35))
    outerCog.curve(to: NSPoint(x: 32.87, y: 38.08),
                   controlPoint1: NSPoint(x: 34.15, y: 36.3),
                   controlPoint2: NSPoint(x: 33.82, y: 37.53))
    outerCog.line(to: NSPoint(x: 31.13, y: 39.08))
    outerCog.curve(to: NSPoint(x: 28.4, y: 38.35),
                   controlPoint1: NSPoint(x: 30.21, y: 39.62),
                   controlPoint2: NSPoint(x: 28.93, y: 39.26))
    outerCog.line(to: NSPoint(x: 27.62, y: 37))
    outerCog.curve(to: NSPoint(x: 26.51, y: 36.53),
                   controlPoint1: NSPoint(x: 27.4, y: 36.61),
                   controlPoint2: NSPoint(x: 26.94, y: 36.42))
    outerCog.curve(to: NSPoint(x: 19.49, y: 36.53),
                   controlPoint1: NSPoint(x: 24.14, y: 37.14),
                   controlPoint2: NSPoint(x: 21.86, y: 37.14))
    outerCog.curve(to: NSPoint(x: 18.38, y: 37),
                   controlPoint1: NSPoint(x: 19.06, y: 36.42),
                   controlPoint2: NSPoint(x: 18.6, y: 36.61))
    outerCog.line(to: NSPoint(x: 17.6, y: 38.35))
    outerCog.curve(to: NSPoint(x: 14.87, y: 39.08),
                   controlPoint1: NSPoint(x: 17.07, y: 39.26),
                   controlPoint2: NSPoint(x: 15.79, y: 39.62))
    outerCog.line(to: NSPoint(x: 13.13, y: 38.08))
    outerCog.curve(to: NSPoint(x: 12.4, y: 35.35),
                   controlPoint1: NSPoint(x: 12.18, y: 37.53),
                   controlPoint2: NSPoint(x: 11.85, y: 36.3))
    outerCog.line(to: NSPoint(x: 13.18, y: 34.01))
    outerCog.curve(to: NSPoint(x: 13.02, y: 32.81),
                   controlPoint1: NSPoint(x: 13.4, y: 33.62),
                   controlPoint2: NSPoint(x: 13.34, y: 33.13))
    outerCog.curve(to: NSPoint(x: 9.52, y: 26.73),
                   controlPoint1: NSPoint(x: 11.36, y: 31.12),
                   controlPoint2: NSPoint(x: 10.15, y: 29.02))
    outerCog.curve(to: NSPoint(x: 8.56, y: 25.99),
                   controlPoint1: NSPoint(x: 9.4, y: 26.29),
                   controlPoint2: NSPoint(x: 9.01, y: 25.99))
    outerCog.line(to: NSPoint(x: 7, y: 25.99))
    outerCog.curve(to: NSPoint(x: 5, y: 23.99),
                   controlPoint1: NSPoint(x: 5.9, y: 25.99),
                   controlPoint2: NSPoint(x: 5, y: 25.1))
    outerCog.line(to: NSPoint(x: 5, y: 21.99))
    outerCog.curve(to: NSPoint(x: 7, y: 19.99),
                   controlPoint1: NSPoint(x: 5, y: 20.89),
                   controlPoint2: NSPoint(x: 5.9, y: 19.99))
    outerCog.line(to: NSPoint(x: 8.56, y: 19.99))
    outerCog.curve(to: NSPoint(x: 9.52, y: 19.26),
                   controlPoint1: NSPoint(x: 9.01, y: 19.99),
                   controlPoint2: NSPoint(x: 9.4, y: 19.69))
    outerCog.curve(to: NSPoint(x: 13.02, y: 13.18),
                   controlPoint1: NSPoint(x: 10.15, y: 16.97),
                   controlPoint2: NSPoint(x: 11.36, y: 14.87))
    outerCog.curve(to: NSPoint(x: 13.18, y: 11.98),
                   controlPoint1: NSPoint(x: 13.34, y: 12.86),
                   controlPoint2: NSPoint(x: 13.4, y: 12.37))
    outerCog.line(to: NSPoint(x: 12.4, y: 10.64))
    outerCog.curve(to: NSPoint(x: 13.13, y: 7.9),
                   controlPoint1: NSPoint(x: 11.85, y: 9.68),
                   controlPoint2: NSPoint(x: 12.18, y: 8.46))
    outerCog.line(to: NSPoint(x: 14.87, y: 6.9))
    outerCog.curve(to: NSPoint(x: 17.6, y: 7.64),
                   controlPoint1: NSPoint(x: 15.79, y: 6.37),
                   controlPoint2: NSPoint(x: 17.07, y: 6.73))
    outerCog.line(to: NSPoint(x: 18.38, y: 8.99))
    outerCog.curve(to: NSPoint(x: 19.49, y: 9.46),
                   controlPoint1: NSPoint(x: 18.6, y: 9.38),
                   controlPoint2: NSPoint(x: 19.06, y: 9.57))
    outerCog.curve(to: NSPoint(x: 26.51, y: 9.45),
                   controlPoint1: NSPoint(x: 21.86, y: 8.84),
                   controlPoint2: NSPoint(x: 24.14, y: 8.84))
    outerCog.curve(to: NSPoint(x: 26.76, y: 9.49),
                   controlPoint1: NSPoint(x: 26.59, y: 9.48),
                   controlPoint2: NSPoint(x: 26.67, y: 9.49))
    outerCog.close()
    outerCog.move(to: NSPoint(x: 30.14, y: 4.64))
    outerCog.curve(to: NSPoint(x: 26.67, y: 6.64),
                   controlPoint1: NSPoint(x: 28.71, y: 4.64),
                   controlPoint2: NSPoint(x: 27.38, y: 5.4))
    outerCog.line(to: NSPoint(x: 26.26, y: 7.34))
    outerCog.curve(to: NSPoint(x: 19.74, y: 7.34),
                   controlPoint1: NSPoint(x: 24.06, y: 6.88),
                   controlPoint2: NSPoint(x: 21.94, y: 6.88))
    outerCog.line(to: NSPoint(x: 19.33, y: 6.64))
    outerCog.curve(to: NSPoint(x: 15.86, y: 4.64),
                   controlPoint1: NSPoint(x: 18.62, y: 5.4),
                   controlPoint2: NSPoint(x: 17.29, y: 4.64))
    outerCog.curve(to: NSPoint(x: 13.87, y: 5.17),
                   controlPoint1: NSPoint(x: 15.16, y: 4.64),
                   controlPoint2: NSPoint(x: 14.47, y: 4.82))
    outerCog.line(to: NSPoint(x: 12.13, y: 6.17))
    outerCog.curve(to: NSPoint(x: 10.67, y: 11.64),
                   controlPoint1: NSPoint(x: 10.22, y: 7.28),
                   controlPoint2: NSPoint(x: 9.57, y: 9.73))
    outerCog.line(to: NSPoint(x: 11.07, y: 12.34))
    outerCog.curve(to: NSPoint(x: 7.81, y: 17.99),
                   controlPoint1: NSPoint(x: 9.61, y: 13.97),
                   controlPoint2: NSPoint(x: 8.5, y: 15.9))
    outerCog.line(to: NSPoint(x: 7, y: 17.99))
    outerCog.curve(to: NSPoint(x: 3, y: 21.99),
                   controlPoint1: NSPoint(x: 4.79, y: 17.99),
                   controlPoint2: NSPoint(x: 3, y: 19.79))
    outerCog.line(to: NSPoint(x: 3, y: 23.99))
    outerCog.curve(to: NSPoint(x: 7, y: 27.99),
                   controlPoint1: NSPoint(x: 3, y: 26.2),
                   controlPoint2: NSPoint(x: 4.79, y: 27.99))
    outerCog.line(to: NSPoint(x: 7.81, y: 27.99))
    outerCog.curve(to: NSPoint(x: 11.07, y: 33.65),
                   controlPoint1: NSPoint(x: 8.5, y: 30.08),
                   controlPoint2: NSPoint(x: 9.61, y: 32.02))
    outerCog.line(to: NSPoint(x: 10.67, y: 34.35))
    outerCog.curve(to: NSPoint(x: 12.13, y: 39.81),
                   controlPoint1: NSPoint(x: 9.57, y: 36.26),
                   controlPoint2: NSPoint(x: 10.22, y: 38.71))
    outerCog.line(to: NSPoint(x: 13.87, y: 40.81))
    outerCog.curve(to: NSPoint(x: 15.86, y: 41.35),
                   controlPoint1: NSPoint(x: 14.47, y: 41.16),
                   controlPoint2: NSPoint(x: 15.16, y: 41.35))
    outerCog.curve(to: NSPoint(x: 19.33, y: 39.35),
                   controlPoint1: NSPoint(x: 17.29, y: 41.35),
                   controlPoint2: NSPoint(x: 18.62, y: 40.58))
    outerCog.line(to: NSPoint(x: 19.74, y: 38.64))
    outerCog.curve(to: NSPoint(x: 26.26, y: 38.64),
                   controlPoint1: NSPoint(x: 21.94, y: 39.11),
                   controlPoint2: NSPoint(x: 24.06, y: 39.11))
    outerCog.line(to: NSPoint(x: 26.67, y: 39.35))
    outerCog.curve(to: NSPoint(x: 30.14, y: 41.35),
                   controlPoint1: NSPoint(x: 27.38, y: 40.58),
                   controlPoint2: NSPoint(x: 28.71, y: 41.35))
    outerCog.curve(to: NSPoint(x: 32.13, y: 40.81),
                   controlPoint1: NSPoint(x: 30.84, y: 41.35),
                   controlPoint2: NSPoint(x: 31.53, y: 41.16))
    outerCog.line(to: NSPoint(x: 33.87, y: 39.81))
    outerCog.curve(to: NSPoint(x: 35.33, y: 34.35),
                   controlPoint1: NSPoint(x: 35.78, y: 38.71),
                   controlPoint2: NSPoint(x: 36.43, y: 36.26))
    outerCog.line(to: NSPoint(x: 34.93, y: 33.65))
    outerCog.curve(to: NSPoint(x: 38.19, y: 27.99),
                   controlPoint1: NSPoint(x: 36.39, y: 32.02),
                   controlPoint2: NSPoint(x: 37.5, y: 30.08))
    outerCog.line(to: NSPoint(x: 39, y: 27.99))
    outerCog.curve(to: NSPoint(x: 43, y: 23.99),
                   controlPoint1: NSPoint(x: 41.21, y: 27.99),
                   controlPoint2: NSPoint(x: 43, y: 26.2))
    outerCog.line(to: NSPoint(x: 43, y: 21.99))
    outerCog.curve(to: NSPoint(x: 39, y: 17.99),
                   controlPoint1: NSPoint(x: 43, y: 19.79),
                   controlPoint2: NSPoint(x: 41.21, y: 17.99))
    outerCog.line(to: NSPoint(x: 38.19, y: 17.99))
    outerCog.curve(to: NSPoint(x: 34.93, y: 12.34),
                   controlPoint1: NSPoint(x: 37.5, y: 15.9),
                   controlPoint2: NSPoint(x: 36.39, y: 13.97))
    outerCog.line(to: NSPoint(x: 35.33, y: 11.64))
    outerCog.curve(to: NSPoint(x: 33.87, y: 6.17),
                   controlPoint1: NSPoint(x: 36.43, y: 9.73),
                   controlPoint2: NSPoint(x: 35.78, y: 7.28))
    outerCog.line(to: NSPoint(x: 32.13, y: 5.17))
    outerCog.curve(to: NSPoint(x: 30.14, y: 4.64),
                   controlPoint1: NSPoint(x: 31.53, y: 4.82),
                   controlPoint2: NSPoint(x: 30.84, y: 4.64))
    outerCog.close()
    outerCog.fill()
    
    let innerCircle = NSBezierPath()
    innerCircle.move(to: NSPoint(x: 23, y: 29.99))
    innerCircle.curve(to: NSPoint(x: 16, y: 22.99),
                      controlPoint1: NSPoint(x: 19.14, y: 29.99),
                      controlPoint2: NSPoint(x: 16, y: 26.85))
    innerCircle.curve(to: NSPoint(x: 23, y: 15.99),
                      controlPoint1: NSPoint(x: 16, y: 19.13),
                      controlPoint2: NSPoint(x: 19.14, y: 15.99))
    innerCircle.curve(to: NSPoint(x: 30, y: 22.99),
                      controlPoint1: NSPoint(x: 26.86, y: 15.99),
                      controlPoint2: NSPoint(x: 30, y: 19.13))
    innerCircle.curve(to: NSPoint(x: 23, y: 29.99),
                      controlPoint1: NSPoint(x: 30, y: 26.85),
                      controlPoint2: NSPoint(x: 26.86, y: 29.99))
    innerCircle.close()
    innerCircle.move(to: NSPoint(x: 23, y: 13.99))
    innerCircle.curve(to: NSPoint(x: 14, y: 22.99),
                      controlPoint1: NSPoint(x: 18.04, y: 13.99),
                      controlPoint2: NSPoint(x: 14, y: 18.03))
    innerCircle.curve(to: NSPoint(x: 23, y: 31.99),
                      controlPoint1: NSPoint(x: 14, y: 27.96),
                      controlPoint2: NSPoint(x: 18.04, y: 31.99))
    innerCircle.curve(to: NSPoint(x: 32, y: 22.99),
                      controlPoint1: NSPoint(x: 27.96, y: 31.99),
                      controlPoint2: NSPoint(x: 32, y: 27.96))
    innerCircle.curve(to: NSPoint(x: 23, y: 13.99),
                      controlPoint1: NSPoint(x: 32, y: 18.03),
                      controlPoint2: NSPoint(x: 27.96, y: 13.99))
    innerCircle.close()
    innerCircle.fill()
  }
}
