#import <AppKit/AppKit.h>

#import "StatusItemButton.h"

@implementation StatusItemButton

- (instancetype) initWithImage: (NSImage *) image {
    self = [super initWithFrame:NSMakeRect(0, 0, image.size.width, image.size.height)];
    if (self) {
        self.image = image;
    }
    return self;
}

- (void) setImage:(NSImage *)image {
    _image = image;
    [self setNeedsDisplay:YES];
}

- (void) drawRect: (NSRect) dirtyRect {
    NSSize imageSize = self.image.size;
    CGFloat x = (self.bounds.size.width - imageSize.width)/2;
    CGFloat y = (self.bounds.size.height - imageSize.height) /2;
    NSRect drawnRect = NSMakeRect(x, y, imageSize.width, imageSize.height);
    
    [self.image drawInRect:drawnRect fromRect:NSZeroRect operation:NSCompositeSourceOver fraction:1.0];
}

- (void) mouseDown:(NSEvent *)theEvent {
    [self.delegate statusItemButtonLeftClick:self];
}

- (void) rightMouseDown:(NSEvent *)theEvent {
    [self.delegate statusItemButtonRightClick:self];
}



@end
