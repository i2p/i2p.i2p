/*
 * Created on Jun 4, 2005
 */
package net.sf.launch4j;

/**
 * This class allows launch4j to act as a launcher instead of a wrapper.
 * It's useful on Windows because an application cannot be a GUI and console one
 * at the same time. So there are two launchers that start launch4j.jar: launch4j.exe
 * for GUI mode and launch4jc.exe for console operation.
 * The Launcher class is packed into an executable jar that contains nothing else but
 * the manifest with Class-Path attribute defined as in the application jar. The jar
 * is wrapped with launch4j.
 * 
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class Launcher {
	public static void main(String[] args) {
		Main.main(args);
	}
}
