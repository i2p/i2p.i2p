package net.i2p.syndie.data;

import java.io.*;
import java.util.*;
import net.i2p.data.*;
import net.i2p.I2PAppContext;

/**
 * Create a new blog metadata & set of entries using some crazy UTF8 encoded chars,
 * then make sure they're always valid.  These blogs & entries can then be fed into
 * jetty/syndie/etc to see how and where they are getting b0rked.
 */
public class EncodingTestGenerator {
    public EncodingTestGenerator() {}
    public static final String TEST_STRING = "\u20AC\u00DF\u6771\u10400\u00F6";
    
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            Object keys[] = ctx.keyGenerator().generateSigningKeypair();
            SigningPublicKey pub = (SigningPublicKey)keys[0];
            SigningPrivateKey priv = (SigningPrivateKey)keys[1];

            Properties opts = new Properties();
            opts.setProperty("Name", TEST_STRING);
            opts.setProperty("Description", TEST_STRING);
            opts.setProperty("Edition", "0");
            opts.setProperty("ContactURL", TEST_STRING);

            String nameOrig = opts.getProperty("Name");
            BlogInfo info = new BlogInfo(pub, null, opts);
            info.sign(ctx, priv);
            boolean ok = info.verify(ctx);
            System.err.println("sign&verify: " + ok);
            
            FileOutputStream o = new FileOutputStream("encodedMeta.dat");
            info.write(o, true);
            o.close();
            FileInputStream i = new FileInputStream("encodedMeta.dat");
            byte buf[] = new byte[4096];
            int sz = DataHelper.read(i, buf);
            BlogInfo read = new BlogInfo();
            read.load(new ByteArrayInputStream(buf, 0, sz));
            ok = read.verify(ctx);
            System.err.println("write to disk, verify read: " + ok);
            System.err.println("Name ok? " + read.getProperty("Name").equals(TEST_STRING));
            System.err.println("Desc ok? " + read.getProperty("Description").equals(TEST_STRING));
            System.err.println("Name ok? " + read.getProperty("ContactURL").equals(TEST_STRING));
            
            // ok now lets create some entries
            BlogURI uri = new BlogURI(read.getKey().calculateHash(), 0);
            String tags[] = new String[4];
            for (int j = 0; j < tags.length; j++)
                tags[j] = TEST_STRING + "_" + j;
            StringBuffer smlOrig = new StringBuffer(512);
            smlOrig.append("Subject: ").append(TEST_STRING).append("\n\n");
            smlOrig.append("Hi with ").append(TEST_STRING);
            EntryContainer container = new EntryContainer(uri, tags, DataHelper.getUTF8(smlOrig));
            container.seal(ctx, priv, null);
            ok = container.verifySignature(ctx, read);
            System.err.println("Sealed and verified entry: " + ok);
            FileOutputStream fos = new FileOutputStream("encodedEntry.dat");
            container.write(fos, true);
            fos.close();
            System.out.println("Written to " + new File("encodedEntry.dat").getAbsolutePath());
            
            FileInputStream fis = new FileInputStream("encodedEntry.dat");
            EntryContainer read2 = new EntryContainer();
            read2.load(fis);
            ok = read2.verifySignature(ctx, read);
            System.out.println("Read ok? " + ok);
            
            read2.parseRawData(ctx);
            String tagsRead[] = read2.getTags();
            for (int j = 0; j < tagsRead.length; j++) {
                if (!tags[j].equals(tagsRead[j]))
                    System.err.println("Tag error [" + j + "]: read = [" + tagsRead[j] + "] want [" + tags[j] + "]");
                else
                    System.err.println("Tag ok [" + j + "]");
            }
            String readText = read2.getEntry().getText();
            ok = readText.equals(smlOrig.toString());
            System.err.println("SML text ok? " + ok);
        } catch (Exception e) { e.printStackTrace(); }
    }
}
