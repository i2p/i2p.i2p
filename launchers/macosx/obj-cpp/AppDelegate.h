#ifndef __APPDELEGATE_H__
#define __APPDELEGATE_H__

#include <algorithm>
#include <string>
#include <memory>

#include <Cocoa/Cocoa.h>

#include "StatusItemButton.h"
#include "JavaHelper.h"
#include "RouterTask.h"
#include "neither/maybe.hpp"
#include "optional.hpp"
#include "subprocess.hpp"
#include <glob.h>
#include <vector>


#define DEF_I2P_VERSION "0.9.35"
#define APPDOMAIN "net.i2p.launcher"
#define NSAPPDOMAIN @APPDOMAIN
#define CFAPPDOMAIN CFSTR(APPDOMAIN)
#define APP_IDSTR @"I2P Launcher"


using namespace neither;

using maybeAnRouterRunner = std::experimental::optional<RouterTask*>;


extern JvmListSharedPtr gRawJvmList;

// DO NOT ACCESS THIS GLOBAL VARIABLE DIRECTLY.
static std::mutex globalRouterStatusMutex;
static maybeAnRouterRunner globalRouterStatus = maybeAnRouterRunner{};

maybeAnRouterRunner getGlobalRouterObject();
void setGlobalRouterObject(RouterTask* newRouter);

@class ExtractMetaInfo;
@interface ExtractMetaInfo : NSObject
@property (strong) NSString* i2pBase;
@property (strong) NSString* javaBinary;
@property (strong) NSString* zipFile;
@property (strong) NSString* jarFile;
@end

inline void sendUserNotification(NSString* title, NSString* informativeText, NSImage* contentImage = NULL, bool makeSound = false) {
  NSUserNotification *userNotification = [[[NSUserNotification alloc] init] autorelease];

  userNotification.title = title;
  userNotification.informativeText = informativeText;
  if (contentImage != NULL) userNotification.contentImage = contentImage;
  if (makeSound) userNotification.soundName = NSUserNotificationDefaultSoundName;

  [[NSUserNotificationCenter defaultUserNotificationCenter] scheduleNotification:userNotification];
};

inline std::vector<std::string> globVector(const std::string& pattern){
    glob_t glob_result;
    glob(pattern.c_str(),GLOB_TILDE,NULL,&glob_result);
    std::vector<std::string> files;
    for(unsigned int i=0;i<glob_result.gl_pathc;++i){
        files.push_back(std::string(glob_result.gl_pathv[i]));
    }
    globfree(&glob_result);
    return files;
}

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

- (void) openRouterConsoleBtnHandler: (NSMenuItem *) menuItem;
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
@property (copy) NSImage *contentImage NS_AVAILABLE(10_9, NA);
- (void)extractI2PBaseDir:(ExtractMetaInfo *)metaInfo completion:(void(^)(BOOL success, NSError *error))completion;
- (void)startupI2PRouter:(ExtractMetaInfo *)metaInfo;
- (void)applicationDidFinishLaunching:(NSNotification *)aNotification;
- (void)applicationWillTerminate:(NSNotification *)aNotification;
- (void)setApplicationDefaultPreferences;
- (void)userChooseJavaHome;
- (AppDelegate *)initWithArgc:(int)argc argv:(const char **)argv;
- (NSString *)userSelectJavaHome:(JvmListPtr)rawJvmList;
- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
                               shouldPresentNotification:(NSUserNotification *)notification;
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
