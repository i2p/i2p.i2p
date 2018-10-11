//
//  EventManager.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 22/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

import Foundation

// TODO: Log all events?

class EventManager {
  var listeners = Dictionary<String, NSMutableArray>();
  
  // Create a new event listener, not expecting information from the trigger
  // @param eventName: Matching trigger eventNames will cause this listener to fire
  // @param action: The function/lambda you want executed when the event triggers
  func listenTo(eventName:String, action: @escaping (()->())) {
    let newListener = EventListenerAction(callback: action)
    addListener(eventName: eventName, newEventListener: newListener)
  }
  
  // Create a new event listener, expecting information from the trigger
  // @param eventName: Matching trigger eventNames will cause this listener to fire
  // @param action: The function/lambda you want executed when the event triggers
  func listenTo(eventName:String, action: @escaping ((Any?)->())) {
    let newListener = EventListenerAction(callback: action)
    addListener(eventName: eventName, newEventListener: newListener)
  }
  
  internal func addListener(eventName:String, newEventListener:EventListenerAction) {
    if let listenerArray = self.listeners[eventName] {
      listenerArray.add(newEventListener)
    } else {
      self.listeners[eventName] = [newEventListener] as NSMutableArray
    }
  }
  
  // Removes all listeners by default, or specific listeners through paramters
  // @param eventName: If an event name is passed, only listeners for that event will be removed
  func removeListeners(eventNameToRemoveOrNil:String?) {
    if let eventNameToRemove = eventNameToRemoveOrNil {
      if let actionArray = self.listeners[eventNameToRemove] {
        actionArray.removeAllObjects()
      }
    } else {
      self.listeners.removeAll(keepingCapacity: false)
    }
  }
  
  // Triggers an event
  // @param eventName: Matching listener eventNames will fire when this is called
  // @param information: pass values to your listeners
  func trigger(eventName:String, information:Any? = nil) {
    print("Event: ", eventName, " will trigger ", self.listeners[eventName]?.count ?? 0, " listeners")
    if let actionObjects = self.listeners[eventName] {
      for actionObject in actionObjects {
        if let actionToPerform = actionObject as? EventListenerAction {
          if let methodToCall = actionToPerform.actionExpectsInfo {
            methodToCall(information)
          }
          else if let methodToCall = actionToPerform.action {
            methodToCall()
          }
        }
      }
    }
  }
}

// Class to hold actions to live in NSMutableArray
class EventListenerAction {
  let action:(() -> ())?
  let actionExpectsInfo:((Any?) -> ())?
  
  init(callback: @escaping (() -> ()) ) {
    self.action = callback
    self.actionExpectsInfo = nil
  }
  
  init(callback: @escaping ((Any?) -> ()) ) {
    self.actionExpectsInfo = callback
    self.action = nil
  }
}
