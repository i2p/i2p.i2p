package net.i2p.BOB;

import net.i2p.util.Log;

public class Logger {
	public Log log;
	private boolean logToStdout;

	public Logger(Log log, boolean logToStdout) {
		this.log = log;
		this.logToStdout = logToStdout;
	}

	public void info(String msg) {
		if (logToStdout)
			System.out.println("INFO: " + msg);
		if (log.shouldLog(Log.INFO))
			log.info(msg);
	}

	public void warn(String msg) {
		warn(msg, null);
	}

	public void warn(String msg, Throwable e) {
		if (logToStdout) {
			System.out.println("WARNING: " + msg);
			if (e != null)
				e.printStackTrace();
		}
		if (log.shouldLog(Log.WARN))
			log.warn(msg, e);
	}

	public void error(String msg, Throwable e) {
		if (logToStdout) {
			System.out.println("ERROR: " + msg);
			if (e != null)
				e.printStackTrace();
		}
		if (log.shouldLog(Log.ERROR))
			log.error(msg, e);
	}
}
