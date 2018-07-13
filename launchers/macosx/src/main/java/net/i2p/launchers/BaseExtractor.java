package net.i2p.launchers;


import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * As the name suggest, it extracts the base path.
 *
 * @author Meeh
 * @since 0.9.35
 */
public class BaseExtractor extends EnvCheck {

    public boolean printDebug = false;

    public void runExtract(String zipFilename) {
        String destinationPath = this.baseDirPath;
        try(ZipFile file = new ZipFile(zipFilename)) {
            FileSystem fileSystem = FileSystems.getDefault();
            Enumeration<? extends ZipEntry> entries = file.entries();

            try {
                Files.createDirectory(fileSystem.getPath(destinationPath));
            } catch (IOException e) {
                // It's OK to fail here.
            }

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (printDebug) System.out.println("Found entry: "+entry.toString());
                if (entry.isDirectory()) {
                    if (printDebug) System.out.println("Creating Directory:" + destinationPath + "/" + entry.getName());
                    Files.createDirectories(fileSystem.getPath(destinationPath + "/" + entry.getName()));
                } else {
                    InputStream is = file.getInputStream(entry);
                    BufferedInputStream bis = new BufferedInputStream(is);
                    String uncompressedFileName = destinationPath + "/" + entry.getName();
                    Path uncompressedFilePath = fileSystem.getPath(uncompressedFileName);
                    Files.createFile(uncompressedFilePath);
                    FileOutputStream fileOutput = new FileOutputStream(uncompressedFileName);
                    while (bis.available() > 0) fileOutput.write(bis.read());
                    fileOutput.close();
                    if (printDebug) System.out.println("Written :" + entry.getName());
                }
            }
        } catch (IOException e) {
            //
            System.err.println("Exception in extractor!");
            System.err.println(e.toString());
        }
    }

    public BaseExtractor(String[] args) {
        super(args);
    }

    public static void main(String[] args) {
        System.out.println("Starting extraction");
        BaseExtractor be = new BaseExtractor(args);
        String debug = System.getProperty("print.debug");
        if (debug != null) {
            be.printDebug = true;
        }
        be.runExtract(System.getProperty("i2p.base.zip"));
    }
}
