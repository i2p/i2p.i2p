package net.i2p

import net.i2p.router.Router

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
  * @author Meeh
  * @version 0.0.1
  * @since 0.9.35
  */
object MacOSXRouterLauncherApp extends App {


  Router.main(args)
}
