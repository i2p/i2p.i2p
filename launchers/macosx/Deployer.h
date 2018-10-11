//
//  Deployer.h
//  I2PLauncher
//
//  Created by Mikal Villa on 19/09/2018.
//  Copyright © 2018 The I2P Project. All rights reserved.
//

#import <Foundation/Foundation.h>
#import <Foundation/NSError.h>
#import "AppDelegate.h"

@class ExtractMetaInfo;


@interface I2PDeployer : NSObject
@property (assign) ExtractMetaInfo *metaInfo;
- (I2PDeployer *) initWithMetaInfo:(ExtractMetaInfo*)mi;
- (void) extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion;
@end

