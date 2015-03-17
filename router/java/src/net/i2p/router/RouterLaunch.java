package net.i2p.router;

/**
 *  This is the class called by the runplain.sh script on linux
 *  and the i2p.exe launcher on Windows.
 *  (i.e. no wrapper)
 *
 *  Setup of wrapper.log file is moved to WorkingDir.java
 *  Until WorkingDir is called, the existing stdout / stderr will be used.
 */
public class RouterLaunch {

    public static void main(String args[]) {
	Router.main(args);
    }
}
