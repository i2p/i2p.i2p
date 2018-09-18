#ifndef __APPDELEGATE_H__
#define __APPDELEGATE_H__

#include <algorithm>
#include <string>
#include <memory>

#include <Cocoa/Cocoa.h>

#include "RouterTask.h"
#include "StatusItemButton.h"
#include "JavaHelper.h"
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

@class ExtractMetaInfo;
using maybeAnRouterRunner = std::experimental::optional<I2PRouterTask*>;

std::vector<std::string> buildClassPath(std::string basePath);

extern JvmListSharedPtr gRawJvmList;

// DO NOT ACCESS THIS GLOBAL VARIABLE DIRECTLY.
static std::mutex globalRouterStatusMutex;
static maybeAnRouterRunner globalRouterStatus = maybeAnRouterRunner{};
static bool isRuterRunning = false;

maybeAnRouterRunner getGlobalRouterObject();
void setGlobalRouterObject(I2PRouterTask* newRouter);
bool getGlobalRouterIsRunning();
void setGlobalRouterIsRunning(bool running);

@interface ExtractMetaInfo : NSObject
@property (copy) NSString* i2pBase;
@property (copy) NSString* javaBinary;
@property (copy) NSString* zipFile;
@property (copy) NSString* jarFile;
@end

@class I2PStatusMenu;
@interface I2PStatusMenu : NSMenu
- (BOOL)validateMenuItem:(NSMenuItem *)menuItem;
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

inline std::string getDefaultBaseDir()
{
  // Figure out base directory
  const char* pathFromHome = "/Users/%s/Library/I2P";
  auto username = getenv("USER");
  char buffer[strlen(pathFromHome)+strlen(username)];
  sprintf(buffer, pathFromHome, username);
  std::string i2pBaseDir(buffer);
  return i2pBaseDir;
}

@interface MenuBarCtrl : NSObject <StatusItemButtonDelegate, NSMenuDelegate>
@property BOOL enableLogging;
@property BOOL enableVerboseLogging;
@property (strong) I2PStatusMenu *menu;
@property (strong) StatusItemButton* statusBarButton;
@property (strong) NSUserDefaults *userPreferences;
@property (strong, nonatomic) NSImage * image;
@property (strong, nonatomic) NSStatusItem *statusItem;
// Event handlers
- (void) statusItemButtonLeftClick: (StatusItemButton *) button;
- (void) statusItemButtonRightClick: (StatusItemButton *) button;
- (void) statusBarImageBtnClicked;
- (void) btnPressedAction:(id)sender;
- (void) menuWillOpen:(I2PStatusMenu *)menu;

- (void) openRouterConsoleBtnHandler: (NSMenuItem *) menuItem;
- (void) startJavaRouterBtnHandler: (NSMenuItem *) menuItem;
- (void) restartJavaRouterBtnHandler: (NSMenuItem *) menuItem;
- (void) stopJavaRouterBtnHandler: (NSMenuItem *) menuItem;
- (void) quitWrapperBtnHandler: (NSMenuItem *) menuItem;
// Methods
- (MenuBarCtrl *) init;
- (void) dealloc;
- (I2PStatusMenu *) createStatusBarMenu;
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
@property ExtractMetaInfo *metaInfo;
@property (copy) NSImage *contentImage NS_AVAILABLE(10_9, NA);
- (void)extractI2PBaseDir:(void(^)(BOOL success, NSError *error))completion;
- (void)startupI2PRouter;
- (void)applicationDidFinishLaunching:(NSNotification *)aNotification;
- (void)applicationWillTerminate:(NSNotification *)aNotification;
- (void)setApplicationDefaultPreferences;
- (void)userChooseJavaHome;
- (AppDelegate *)initWithArgc:(int)argc argv:(const char **)argv;
- (NSString *)userSelectJavaHome:(JvmListPtr)rawJvmList;
- (BOOL)userNotificationCenter:(NSUserNotificationCenter *)center
                               shouldPresentNotification:(NSUserNotification *)notification;
@end


#endif
