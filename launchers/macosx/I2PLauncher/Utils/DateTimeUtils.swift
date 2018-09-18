//
//  DateTimeUtils.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

extension Date {
  init(date: NSDate) {
    self.init(timeIntervalSinceReferenceDate: date.timeIntervalSinceReferenceDate)
  }
}

extension NSDate {
  convenience init(date: Date) {
    self.init(timeIntervalSinceReferenceDate: date.timeIntervalSinceReferenceDate)
  }
}

class DateTimeUtils {
  static func timeAgoSinceDate(date:NSDate, numericDates:Bool) -> String {
    let calendar = NSCalendar.current
    let unitFlags: Set<Calendar.Component> = [.minute, .hour, .day, .weekOfYear, .month, .year, .second]
    let now = NSDate()
    let earliest = now.earlierDate(date as Date)
    let latest = (earliest == now as Date) ? date : now
    let components = calendar.dateComponents(unitFlags, from: earliest as Date,  to: latest as Date)
    
    if (components.year! >= 2) {
      return "\(components.year!) years ago"
    } else if (components.year! >= 1){
      if (numericDates){
        return "1 year ago"
      } else {
        return "Last year"
      }
    } else if (components.month! >= 2) {
      return "\(components.month!) months ago"
    } else if (components.month! >= 1){
      if (numericDates){
        return "1 month ago"
      } else {
        return "Last month"
      }
    } else if (components.weekOfYear! >= 2) {
      return "\(components.weekOfYear!) weeks ago"
    } else if (components.weekOfYear! >= 1){
      if (numericDates){
        return "1 week ago"
      } else {
        return "Last week"
      }
    } else if (components.day! >= 2) {
      return "\(components.day!) days ago"
    } else if (components.day! >= 1){
      if (numericDates){
        return "1 day ago"
      } else {
        return "Yesterday"
      }
    } else if (components.hour! >= 2) {
      return "\(components.hour!) hours ago"
    } else if (components.hour! >= 1){
      if (numericDates){
        return "1 hour ago"
      } else {
        return "An hour ago"
      }
    } else if (components.minute! >= 2) {
      return "\(components.minute!) minutes ago"
    } else if (components.minute! >= 1){
      if (numericDates){
        return "1 minute ago"
      } else {
        return "A minute ago"
      }
    } else if (components.second! >= 3) {
      return "\(components.second!) seconds ago"
    } else {
      return "Just now"
    }
    
  }
}
