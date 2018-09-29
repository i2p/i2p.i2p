package net.i2p.launchers;


import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.Files.*;

/**
 * As the name suggest, it extracts the base path.
 *
 * @author Meeh
 * @since 0.9.35
 */
public class BaseExtractor extends EnvCheck {

    public boolean printDebug = false;

    public void unzip(final Path zipFile) {
        try {
            String destinationPath = this.baseDirPath;
            final Path destDir = Files.createDirectories(FileSystems.getDefault().getPath(destinationPath));
            if (notExists(destDir)) {
                createDirectories(destDir);
            }

            try (FileSystem zipFileSystem = FileSystems.newFileSystem(zipFile, null)) {
                final Path root = zipFileSystem.getRootDirectories().iterator().next();

                walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file,
                                                     BasicFileAttributes attrs) throws IOException {
                        final Path destFile = Paths.get(destDir.toString(), file.toString());
                        try {
                            copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (DirectoryNotEmptyException ignore) {
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir,
                                                             BasicFileAttributes attrs) throws IOException {
                        final Path dirToCreate = Paths.get(destDir.toString(), dir.toString());
                        if (notExists(dirToCreate)) {
                            createDirectory(dirToCreate);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
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
        be.unzip( FileSystems.getDefault().getPath(System.getProperty("i2p.base.zip")) );
    }
}
