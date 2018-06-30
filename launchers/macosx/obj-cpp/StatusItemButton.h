#pragma once

#import <Cocoa/Cocoa.h>

@class StatusItemButton;

@protocol StatusItemButtonDelegate <NSObject>

- (void) statusItemButtonLeftClick: (StatusItemButton *) button;
- (void) statusItemButtonRightClick: (StatusItemButton *) button;

@end

@interface StatusItemButton : NSView

@property (strong, nonatomic) NSImage *image;
@property (unsafe_unretained) id<StatusItemButtonDelegate> delegate;

- (instancetype) initWithImage: (NSImage *) image;

@end
