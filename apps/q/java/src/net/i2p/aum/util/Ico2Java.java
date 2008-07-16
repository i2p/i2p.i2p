package net.i2p.aum.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;

/**
 * development utility - converts Favicon.ico to
 * a java source file net.i2p.aum.q.Favicon.java, containing
 * the favicon image
 */
public class Ico2Java {

    public static void convertToJava(String name) throws Exception {

        File f = new File(name);

        byte [] image = new byte[(int)f.length()];
        InputStream fi = new FileInputStream(f);
        fi.read(image);

        // ok, now generate a java file
        String basename = name.substring(0, name.length()-4);
        String jName = "src/net/i2p/aum/q/" + basename + ".java";
        FileWriter fo = new FileWriter(jName);
        fo.write("package net.i2p.aum.q;\n");
        fo.write("public class "+basename+" {\n");
        fo.write("    public static byte [] image = {");
        for (int i=0; i<image.length; i++) {
            if (i % 16 == 0) {
                fo.write("\n        ");
            }
            fo.write(String.valueOf(image[i])+", ");
        }
        
        fo.write("\n        };\n");
        fo.write("}\n");
        fo.close();
    }

    public static void main(String [] args) {
        if (args.length != 1) {
            System.out.println("Usage: ico2java filename.ico");
            System.exit(1);
        }

        File f = new File(args[0]);
        if (!f.isFile()) {
            System.out.println("No such file '"+args[0]+"'");
            System.exit(1);
        }

        try {
            convertToJava(args[0]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

