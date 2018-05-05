package net.i2p.launchers.osx

/**
  *
  *
  * @author Meeh
  * @since 0.9.35
  */
class SystemTrayManager {

  object RouterState {
    var isRunning: Boolean = false
    var startupTime: Long = 0L
  }

  def isRunning = RouterState.isRunning

  def setRunning(runs: Boolean): Unit = {
    if (runs) setStartupTime()
    RouterState.isRunning = runs
  }

  def setStartupTime() = {
    RouterState.startupTime = System.currentTimeMillis / 1000
  }

}
