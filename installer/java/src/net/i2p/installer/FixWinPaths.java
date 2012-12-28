package net.i2p.installer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;


/**
 * Usage: FixWinPaths WrapperConfigFile
 *
 * only for use by installer
 */
public class FixWinPaths{
    public static void main(String args[]) {
        if(args.length != 1) {
            System.err.println("Usage: FixWinPaths [wrapper.conf]\r\n");
            System.exit(1);
        }

        // This is only intended for Windows systems
        if(!System.getProperty("os.name").startsWith("Win")) {
            return;
        }
        replace(args[0]);

    }
    private static void replace(String file) {
        //  Shouldn't be true
        if (!file.contains("wrapper.conf"))
            return;
        String wConf = file;
        String wConfTemp = wConf + ".tmp";

        BufferedReader br = null;
        BufferedWriter bw = null;
        try {
            br = new BufferedReader(new FileReader(wConf));
            bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(wConfTemp), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("wrapper.logfile="))
                    line = "wrapper.logfile=%appdata%\\i2p\\wrapper.log";
                else if (line.startsWith("#wrapper.java.pidfile="))
                    line = "#wrapper.java.pidfile=%appdata%\\i2p\\routerjvm.pid";
                else if (line.startsWith("#wrapper.pidfile="))
                    line = "#wrapper.pidfile=%appdata%\\i2p\\i2p.pid";
                if (line.contains("\\i2p/"))
                    line = line.replace("\\i2p/", "\\i2p\\");
                if (line.contains("lib/"))
                    line = line.replace("lib/", "lib\\");
                if (line.contains("\\/"))
                    line = line.replace("\\/", "\\");
                if (line.contains("logs/log-router"))
                    line = line.replace("logs/log-router", "logs\\log-router");
                bw.write(line);
                bw.newLine();
            }
        } catch (IOException e) {
            return;
        } finally {
            try {
                if(br != null)
                    br.close();
            } catch (IOException e) {
                //
            }
            try {
                if(bw != null)
                    bw.close();
            } catch (IOException e) {
                //
            }
        }
        // Once everything is complete, delete the original wrapper.conf
        File oldFile = new File(wConf);
        oldFile.delete();

        // ...and rename temp file's name to wrapper.conf
        File newFile = new File(wConfTemp);
        newFile.renameTo(oldFile);

    }
}
