//
//  SBridge.h
//  I2PLauncher
//
//  Created by Mikal Villa on 18/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

#import <Foundation/Foundation.h>

@interface SBridge : NSObject
- (NSString*) buildClassPath:(NSString*)i2pPath;
- (void) startupI2PRouter:(NSString*)i2pRootPath javaBinPath:(NSString*)javaBinPath;
- (void) openUrl:(NSString*)url;
@end
