#pragma once

#import <Cocoa/Cocoa.h>

/**
 *
 * This is a class representing the "image" in the systray.
 *
 *
 * **/
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
