package net.i2p

import net.i2p.router.Router
import net.i2p.launchers.OSXDeployment
import java.io.File

/**
  *
  * For java developers:
  * A scala object is like an instance of a class.
  * If you define a method inside an object, it's equals to
  * java's static methods.
  *
  * Also, in scala, the body of a class/object is executed as it's
  * constructor.
  *
  * Also noteworthy;
  * val is immutable
  * var is mutable
  *
  *
  * The i2p base directory in the build should be in a relative path from
  * the launcher, which would be ../Resources/i2pbase - this directory would
  * need to be copied out to a "writable" area, since we're in a signed "immutable"
  * bundle. First this launcher will check if i2pbase is already deployed to a
  * writable area, if it's not, it deploys, if the i2pbase directory has an older
  * version than the one in the bundle, it upgrades. It does nothing if versions
  * matches.
  *
  *
  * @author Meeh
  * @version 0.0.1
  * @since 0.9.35
  */
object MacOSXRouterLauncherApp extends App {

  val i2pBaseBundleDir = new File(new File("."), "../Resources/i2pbase")

  new OSXDeployment()

  Router.main(args)
}
