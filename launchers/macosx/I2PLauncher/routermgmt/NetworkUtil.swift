//
//  NetworkUtil.swift
//  I2PLauncher
//
//  Created by Mikal Villa on 07/04/2019.
//  Copyright © 2019 The I2P Project. All rights reserved.
//

import Foundation

class NetworkUtil {
  static func checkTcpPortForListen(host: String = "127.0.0.1", port: in_port_t = 7657) -> (Bool, descr: String){
    
    let socketFileDescriptor = socket(AF_INET, SOCK_STREAM, 0)
    if socketFileDescriptor == -1 {
      return (false, "SocketCreationFailed, \(descriptionOfLastError())")
    }
    
    var addr = sockaddr_in()
    let sizeOfSockkAddr = MemoryLayout<sockaddr_in>.size
    addr.sin_len = __uint8_t(sizeOfSockkAddr)
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = Int(OSHostByteOrder()) == OSLittleEndian ? _OSSwapInt16(port) : port
    addr.sin_addr = in_addr(s_addr: inet_addr(host))
    addr.sin_zero = (0, 0, 0, 0, 0, 0, 0, 0)
    var bind_addr = sockaddr()
    memcpy(&bind_addr, &addr, Int(sizeOfSockkAddr))
    
    if Darwin.bind(socketFileDescriptor, &bind_addr, socklen_t(sizeOfSockkAddr)) == -1 {
      let details = descriptionOfLastError()
      release(socket: socketFileDescriptor)
      return (false, "\(port), BindFailed, \(details)")
    }
    if listen(socketFileDescriptor, SOMAXCONN ) == -1 {
      let details = descriptionOfLastError()
      release(socket: socketFileDescriptor)
      return (false, "\(port), ListenFailed, \(details)")
    }
    release(socket: socketFileDescriptor)
    return (true, "\(port) is free for use")
  }
  
  static func release(socket: Int32) {
    Darwin.shutdown(socket, SHUT_RDWR)
    close(socket)
  }
  static func descriptionOfLastError() -> String {
    return String(cString: UnsafePointer(strerror(errno)))
  }
}
