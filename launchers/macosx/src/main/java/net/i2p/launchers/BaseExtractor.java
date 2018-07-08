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

    private void runExtract(String zipFilename, String destinationPath) {
        try(ZipFile file = new ZipFile(zipFilename)) {
            FileSystem fileSystem = FileSystems.getDefault();
            Enumeration<? extends ZipEntry> entries = file.entries();
            Files.createDirectory(fileSystem.getPath(destinationPath));
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    System.out.println("Creating Directory:" + destinationPath + entry.getName());
                    Files.createDirectories(fileSystem.getPath(destinationPath + entry.getName()));
                } else {
                    InputStream is = file.getInputStream(entry);
                    BufferedInputStream bis = new BufferedInputStream(is);
                    String uncompressedFileName = destinationPath + entry.getName();
                    Path uncompressedFilePath = fileSystem.getPath(uncompressedFileName);
                    Files.createFile(uncompressedFilePath);
                    FileOutputStream fileOutput = new FileOutputStream(uncompressedFileName);
                    while (bis.available() > 0) fileOutput.write(bis.read());
                    fileOutput.close();
                    System.out.println("Written :" + entry.getName());
                }
            }
        } catch (IOException e) {
            //
        }
    }

    public BaseExtractor(String[] args) {
        super(args);

        if (args.length == 2) {
            if ("extract".equals(args[0])) {
                // Start extract

            }this.runExtract(System.getProperty("i2p.base.zip"),this.baseDirPath);
        }
    }

    public static void main(String[] args) {
        new BaseExtractor(args);
    }
}
