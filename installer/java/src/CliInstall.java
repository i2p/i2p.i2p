import java.io.*;

public class CliInstall extends Install {

    private BufferedReader _in;
    private PrintStream _out;
    
    public CliInstall() {
	_out = System.out;
	_in = new BufferedReader(new InputStreamReader(System.in));
    }

    public void showStatus(String s) {
	_out.println(s);
    }
    
    public void showOptError(String s) {
	_out.println(s);
    }

    public void handleOptInfo(String s) {
	_out.println(s);
    }

    public void startOptCategory(String s) {
	_out.println("* "+s+"\n");
    }

    public void finishOptions() {}

    public void handleOption(int number, String question,
			     String def, String type) {
	Object value;
	while(true) {
	    String answer;
	    _out.print(question+(def == null?"": (" ["+def+"]"))+": ");
	    answer = readLine();
	    if ("".equals(answer) && def != null) {
		answer = def;
	    }
	    if (setOption(number,answer)) break;
	}
    }

    public boolean confirmOption(String question, boolean defaultYes) {
	_out.print(question);
	return readBool(defaultYes);
    }

    private String readLine() {
	try {
	    return _in.readLine().trim();
	} catch (IOException ex) {
	    ex.printStackTrace();
	    System.exit(1);
	    return null;
	}
    }
    
    private boolean readBool(boolean defaultYes) {
	String str = readLine().toLowerCase();
	if ("".equals(str)) return defaultYes;
	return "yes".equals(str) || "y".equals(str) || "true".equals(str)
	    || "ok".equals(str) || "sure".equals(str)
	    || "whatever".equals(str);
    }
}
