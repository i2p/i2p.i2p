package i2p.bote.folder;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;

import net.i2p.util.Log;

/**
 * 
 * @author HungryHobo@mail.i2p
 *
 * @param <T> The type of objects the folder can store.
 */
public abstract class Folder<T extends FolderElement> implements Iterable<T> {
    private Log log = new Log(Folder.class);
    protected File storageDir;
    protected String fileExtension;

    protected Folder(File storageDir, String fileExtension) {
        this.storageDir = storageDir;
        this.fileExtension = fileExtension;
        
        if (!storageDir.exists() && !storageDir.mkdirs())
            log.error("Can't create directory: '" + storageDir + "'");
    }

    public File getStorageDirectory() {
        return storageDir;
    }

    public Collection<T> getElements() {
        Collection<T> elements = new ArrayList<T>();
        Iterator<T> iterator = iterator();
        while (iterator.hasNext())
            elements.add(iterator.next());
        return elements;
    }
    
    // An {@link Iterator} implementation that loads one file into memory at a time.
    @Override
    public final Iterator<T> iterator() {
        final File[] files = storageDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toUpperCase().endsWith(fileExtension.toUpperCase());
            }
        });
        log.debug(files.length + " files with the extension '" + fileExtension + "' found in '" + storageDir + "'.");

        // sort files by date, newest first
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return (int)Math.signum(f1.lastModified() - f2.lastModified());
            }
        });

        return new Iterator<T>() {
            int nextIndex = 0;
            
            @Override
            public boolean hasNext() {
                return nextIndex < files.length;
            }

            @Override
            public T next() {
                File file = files[nextIndex];
                String filePath = file.getAbsolutePath();
                log.info("Reading file: '" + filePath + "'");
                try {
                    T nextElement = createFolderElement(file);
                    nextElement.setFile(file);
                    nextIndex++;
                    return nextElement;
                }
                catch (Exception e) {
                    log.error("Can't create a FolderElement from file: " + filePath, e);
                    return null;
                }
            }
            
            @Override
            public void remove() {
                throw new UnsupportedOperationException("Use the Folder instance to delete a folder element.");
            }
        };
    }
    
    protected abstract T createFolderElement(File file) throws Exception;
}