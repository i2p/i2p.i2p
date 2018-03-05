package net.i2p.util;
    
import java.io.File;
import java.io.FileFilter;
import java.util.Locale;

/**
 * A FileFilter that accepts regular files
 * with a suffix and optionally a prefix, case-insensitive.
 *
 * @since 0.9.34
 */
public class FileSuffixFilter implements FileFilter {

    private final String begin, end;

    /**
     *  A filter that accepts regular files that
     *  end with suffix, case-insensitive.
     */
    public FileSuffixFilter(String suffix) {
        begin = null;
        end = suffix.toLowerCase(Locale.US);
    }

    /**
     *  A filter that accepts regular files that
     *  start with prefix and
     *  end with suffix, case-insensitive.
     */
    public FileSuffixFilter(String prefix, String suffix) {
        begin = prefix.toLowerCase(Locale.US);
        end = suffix.toLowerCase(Locale.US);
    }

    public boolean accept(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(end) &&
               (begin == null || name.startsWith(begin)) &&
               file.isFile();
    }
}
