#ifndef __APPDELEGATE_H__
#define __APPDELEGATE_H__

#include <algorithm>
#include <string>
#include <memory>

#include <Cocoa/Cocoa.h>

#include "StatusItemButton.h"
#include "JavaHelper.h"

extern JvmListSharedPtr gRawJvmList;

@interface MenuBarCtrl : NSObject <StatusItemButtonDelegate, NSMenuDelegate>
@property BOOL enableLogging;
@property BOOL enableVerboseLogging;
@property (strong) NSMenu *menu;
@property (strong) StatusItemButton* statusBarButton;
@property (strong) NSUserDefaults *userPreferences;
@property (strong, nonatomic) NSImage * image;
@property (strong, nonatomic) NSStatusItem *statusItem;
// Event handlers
- (void) statusItemButtonLeftClick: (StatusItemButton *) button;
- (void) statusItemButtonRightClick: (StatusItemButton *) button;
- (void) statusBarImageBtnClicked;
- (void) btnPressedAction:(id)sender;
- (void) menuWillOpen:(NSMenu *)menu;

- (void) startJavaRouterBtnHandler: (NSMenuItem *) menuItem;
- (void) restartJavaRouterBtnHandler: (NSMenuItem *) menuItem;
- (void) stopJavaRouterBtnHandler: (NSMenuItem *) menuItem;
- (void) quitWrapperBtnHandler: (NSMenuItem *) menuItem;
// Methods
- (MenuBarCtrl *) init;
- (void) dealloc;
- (NSMenu *) createStatusBarMenu;
@end

@protocol MenuBarCtrlDelegate
- (void) menuBarCtrlStatusChanged: (BOOL) active;
@end

@interface AppDelegate : NSObject <NSUserNotificationCenterDelegate, NSApplicationDelegate> {
@public
  //NSImageView *imageCell;
}
@property (strong) MenuBarCtrl *menuBarCtrl;
@property (strong) NSUserDefaults *userPreferences;
@property BOOL enableLogging;
@property BOOL enableVerboseLogging;
- (void)applicationDidFinishLaunching:(NSNotification *)aNotification;
- (void)applicationWillTerminate:(NSNotification *)aNotification;
- (void)setApplicationDefaultPreferences;
- (void)userChooseJavaHome;
- (AppDelegate *)initWithArgc:(int)argc argv:(const char **)argv;
- (NSString *)userSelectJavaHome:(JvmListPtr)rawJvmList;
@end


/*


@implementation CNSStatusBarCtrl
-(id)initWithSysTray:(I2PCtrlSysIcon *)sys
{
  self = [super init];
  if (self) {
    item = [[[NSStatusBar systemStatusBar] statusItemWithLength:NSSquareStatusItemLength] retain];
    menu = 0;
    systray = sys;
    imageCell = [[NSImageView alloc] initWithParent:self];
    [item setView: imageCell];
    [item setHidden: NO];
    CFShow(CFSTR("CNSStatusBarCtrl::initWithSysTray executed"));
  }
  return self;
}
-(NSStatusItem*)item {
    return item;
}
-(void)dealloc {
  [[NSStatusBar systemStatusBar] removeStatusItem:item];
  [[NSNotificationCenter defaultCenter] removeObserver:imageCell];
  [imageCell release];
  [item release];
  [super dealloc];
}
@end


class CSystemTrayIcon
{
public:
  CSystemTrayIcon(I2PCtrlSysIcon *sys)
  {
    item = [[CNSStatusBarCtrl alloc] initWithSysTray:sys];
    [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:item];
    const int menuHeight = [[NSStatusBar systemStatusBar] thickness];
    printf("menuHeight: %d\n", menuHeight);
    [[[item item] view] setHidden: NO];
  }
  ~CSystemTrayIcon()
  {
    [[[item item] view] setHidden: YES];
    [[NSUserNotificationCenter defaultUserNotificationCenter] setDelegate:nil];
    [item release];
  }
  CNSStatusBarCtrl *item;
};
*/


#endif
