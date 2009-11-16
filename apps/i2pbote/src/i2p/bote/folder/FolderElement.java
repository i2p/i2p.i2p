package i2p.bote.folder;

import java.io.File;
import java.io.OutputStream;

public interface FolderElement {

    void setFile(File file);
    
    /**
     * Returns the {@link File} from which this packet was read, or <code>null</code>.
     * @return
     */
    File getFile();
    
    void writeTo(OutputStream outputStream) throws Exception;
}