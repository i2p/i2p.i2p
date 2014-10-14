package org.klomp.snark.web;

import java.io.File;
import java.io.Serializable;
import java.text.Collator;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

import org.klomp.snark.MetaInfo;
import org.klomp.snark.Snark;
import org.klomp.snark.Storage;

/**
 *  Comparators for various columns
 *
 *  @since 0.9.16 from TorrentNameComparator, moved from I2PSnarkservlet
 */
class Sorters {

    /**
     *  Negative is reverse
     *
     *<ul>
     *<li>0, 1: Name
     *<li>2: Status
     *<li>3: Peers
     *<li>4: ETA
     *<li>5: Size
     *<li>6: Downloaded
     *<li>7: Uploaded
     *<li>8: Down rate
     *<li>9: Up rate
     *<li>10: Remaining (needed)
     *<li>11: Upload ratio
     *<li>12: File type
     *</ul>
     *
     *  @param servlet for file type callback only
     */
    public static Comparator<Snark> getComparator(int type, I2PSnarkServlet servlet) {
        boolean rev = type < 0;
        Comparator<Snark> rv;
        switch (type) {

          case -1:
          case 0:
          case 1:
          default:
              rv = new TorrentNameComparator();
              if (rev)
                  rv = Collections.reverseOrder(rv);
              break;

          case -2:
          case 2:
              rv = new StatusComparator(rev);
              break;

          case -3:
          case 3:
              rv = new PeersComparator(rev);
              break;

          case -4:
          case 4:
              rv = new ETAComparator(rev);
              break;

          case -5:
          case 5:
              rv = new SizeComparator(rev);
              break;

          case -6:
          case 6:
              rv = new DownloadedComparator(rev);
              break;

          case -7:
          case 7:
              rv = new UploadedComparator(rev);
              break;

          case -8:
          case 8:
              rv = new DownRateComparator(rev);
              break;

          case -9:
          case 9:
              rv = new UpRateComparator(rev);
              break;

          case -10:
          case 10:
              rv = new RemainingComparator(rev);
              break;

          case -11:
          case 11:
              rv = new RatioComparator(rev);
              break;

          case -12:
          case 12:
              rv = new FileTypeComparator(rev, servlet);
              break;

        }
        return rv;
    }


    /**
     *  Sort alphabetically in current locale, ignore case, ignore leading "the "
     *  (I guess this is worth it, a lot of torrents start with "The "
     *  @since 0.7.14
     */
    private static class TorrentNameComparator implements Comparator<Snark>, Serializable {

        public int compare(Snark l, Snark r) {
            return comp(l, r);
        }

        public static int comp(Snark l, Snark r) {
            // put downloads and magnets first
            if (l.getStorage() == null && r.getStorage() != null)
                return -1;
            if (l.getStorage() != null && r.getStorage() == null)
                return 1;
            String ls = l.getBaseName();
            String llc = ls.toLowerCase(Locale.US);
            if (llc.startsWith("the ") || llc.startsWith("the.") || llc.startsWith("the_"))
                ls = ls.substring(4);
            String rs = r.getBaseName();
            String rlc = rs.toLowerCase(Locale.US);
            if (rlc.startsWith("the ") || rlc.startsWith("the.") || rlc.startsWith("the_"))
                rs = rs.substring(4);
            return Collator.getInstance().compare(ls, rs);
        }
    }

    /**
     *  Forward or reverse sort, but the fallback is always forward
     */
    private static abstract class Sort implements Comparator<Snark>, Serializable {

        private final boolean _rev;

        public Sort(boolean rev) {
            _rev = rev;
        }

        public int compare(Snark l, Snark r) {
            int rv = compareIt(l, r);
            if (rv != 0)
                return _rev ? 0 - rv : rv;
            return TorrentNameComparator.comp(l, r);
        }

        protected abstract int compareIt(Snark l, Snark r);

        protected static int compLong(long l, long r) {
            if (l < r)
                return -1;
            if (l > r)
                return 1;
            return 0;
        }
    }


    private static class StatusComparator extends Sort {

        private StatusComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            int rv = getStatus(l) - getStatus(r);
            if (rv != 0)
                return rv;
            // use reverse remaining as first tie break
            return compLong(r.getNeededLength(), l.getNeededLength());
        }

        private static int getStatus(Snark snark) {
            long remaining = snark.getRemainingLength(); 
            if (snark.isStopped()) {
                if (remaining < 0)
                    return 0;
                if (remaining > 0)
                    return 5;
                return 10;
            }
            if (snark.isStarting())
                return 15;
            if (snark.isAllocating())
                return 20;
            if (remaining < 0)
                return 15; // magnet
            if (remaining == 0)
                return 100;
            if (snark.isChecking())
                return 95;
            if (snark.getNeededLength() <= 0)
                return 90;
            if (snark.getPeerCount() <= 0)
                return 40;
            if (snark.getDownloadRate() <= 0)
                return 50;
            return 60;
        }
    }

    private static class PeersComparator extends Sort {

        public PeersComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return l.getPeerCount() - r.getPeerCount();
        }
    }

    private static class RemainingComparator extends Sort {

        public RemainingComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getNeededLength(), r.getNeededLength());
        }
    }

    private static class ETAComparator extends Sort {

        public ETAComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return compLong(eta(l), eta(r));
        }

        private static long eta(Snark snark) {
            long needed = snark.getNeededLength(); 
            if (needed <= 0)
                return 0;
            long total = snark.getTotalLength();
            if (needed > total)
                needed = total;
            long downBps = snark.getDownloadRate();
            if (downBps > 0)
                return needed / downBps;
            return Long.MAX_VALUE;
        }
    }

    private static class SizeComparator extends Sort {

        public SizeComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getTotalLength(), r.getTotalLength());
        }
    }

    private static class DownloadedComparator extends Sort {

        public DownloadedComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            long ld = l.getTotalLength() - l.getRemainingLength();
            long rd = r.getTotalLength() - r.getRemainingLength();
            return compLong(ld, rd);
        }
    }

    private static class UploadedComparator extends Sort {

        public UploadedComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getUploaded(), r.getUploaded());
        }
    }

    private static class DownRateComparator extends Sort {

        public DownRateComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getDownloadRate(), r.getDownloadRate());
        }
    }

    private static class UpRateComparator extends Sort {

        public UpRateComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            return compLong(l.getUploadRate(), r.getUploadRate());
        }
    }

    private static class RatioComparator extends Sort {

        private static final long M = 128 * 1024 * 1024;

        public RatioComparator(boolean rev) { super(rev); }

        public int compareIt(Snark l, Snark r) {
            long lt = l.getTotalLength();
            long ld = lt > 0 ? ((M * l.getUploaded()) / lt) : 0;
            long rt = r.getTotalLength();
            long rd = rt > 0 ? ((M * r.getUploaded()) / rt) : 0;
            return compLong(ld, rd);
        }
    }

    private static class FileTypeComparator extends Sort {

        private final I2PSnarkServlet servlet;

        public FileTypeComparator(boolean rev, I2PSnarkServlet servlet) {
            super(rev);
            this.servlet = servlet;
        }

        public int compareIt(Snark l, Snark r) {
            String ls = toName(l);
            String rs = toName(r);
            return ls.compareTo(rs);
        }

        private String toName(Snark snark) {
            MetaInfo meta = snark.getMetaInfo();
            if (meta == null)
                return "0";
            if (meta.getFiles() != null)
                return "1";
            // arbitrary sort based on icon name
            return servlet.toIcon(meta.getName());
        }
    }

    ////////////// Comparators for details page below

    /**
     *  Class to precompute and efficiently sort data
     *  on a torrent file entry.
     */
    public static class FileAndIndex {
        public final File file;
        public final boolean isDirectory;
        public final long length;
        public final long remaining;
        public final int priority;
        public final int index;

        /**
         *  @param storage may be null
         */
        public FileAndIndex(File file, Storage storage) {
            this.file = file;
            index = storage != null ? storage.indexOf(file) : -1;
            if (index >= 0) {
                isDirectory = false;
                remaining = storage.remaining(index);
                priority = storage.getPriority(index);
            } else {
                isDirectory = file.isDirectory();
                remaining = -1;
                priority = -999;
            }
            length = isDirectory ? 0 : file.length();
        }
    }


    /**
     *  Negative is reverse
     *
     *<ul>
     *<li>0, 1: Name
     *<li>5: Size
     *<li>10: Remaining (needed)
     *<li>12: File type
     *<li>13: Priority
     *</ul>
     *
     *  @param servlet for file type callback only
     */
    public static Comparator<FileAndIndex> getFileComparator(int type, I2PSnarkServlet servlet) {
        boolean rev = type < 0;
        Comparator<FileAndIndex> rv;

        switch (type) {

          case -1:
          case 0:
          case 1:
          default:
              rv = new FileNameComparator();
              if (rev)
                  rv = Collections.reverseOrder(rv);
              break;

          case -5:
          case 5:
              rv = new FAISizeComparator(rev);
              break;

          case -10:
          case 10:
              rv = new FAIRemainingComparator(rev);
              break;

          case -12:
          case 12:
              rv = new FAITypeComparator(rev, servlet);
              break;

          case -13:
          case 13:
              rv = new FAIPriorityComparator(rev);
              break;

        }
        return rv;
    }

    /**
     *  Sort alphabetically in current locale, ignore case,
     *  directories first
     *  @since 0.9.6 moved from I2PSnarkServlet in 0.9.16
     */
    private static class FileNameComparator implements Comparator<FileAndIndex>, Serializable {

        public int compare(FileAndIndex l, FileAndIndex r) {
            return comp(l, r);
        }

        public static int comp(FileAndIndex l, FileAndIndex r) {
            boolean ld = l.isDirectory;
            boolean rd = r.isDirectory;
            if (ld && !rd)
                return -1;
            if (rd && !ld)
                return 1;
            return Collator.getInstance().compare(l.file.getName(), r.file.getName());
        }
    }

    /**
     *  Forward or reverse sort, but the fallback is always forward
     */
    private static abstract class FAISort implements Comparator<FileAndIndex>, Serializable {

        private final boolean _rev;

        public FAISort(boolean rev) {
            _rev = rev;
        }

        public int compare(FileAndIndex l, FileAndIndex r) {
            int rv = compareIt(l, r);
            if (rv != 0)
                return _rev ? 0 - rv : rv;
            return FileNameComparator.comp(l, r);
        }

        protected abstract int compareIt(FileAndIndex l, FileAndIndex r);

        protected static int compLong(long l, long r) {
            if (l < r)
                return -1;
            if (l > r)
                return 1;
            return 0;
        }
    }

    private static class FAIRemainingComparator extends FAISort {

        public FAIRemainingComparator(boolean rev) { super(rev); }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return compLong(l.remaining, r.remaining);
        }
    }

    private static class FAISizeComparator extends FAISort {

        public FAISizeComparator(boolean rev) { super(rev); }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return compLong(l.length, r.length);
        }
    }

    private static class FAITypeComparator extends FAISort {

        private final I2PSnarkServlet servlet;

        public FAITypeComparator(boolean rev, I2PSnarkServlet servlet) {
            super(rev);
            this.servlet = servlet;
        }

        public int compareIt(FileAndIndex l, FileAndIndex r) {
            String ls = toName(l);
            String rs = toName(r);
            return ls.compareTo(rs);
        }

        private String toName(FileAndIndex fai) {
            if (fai.isDirectory)
                return "0";
            // arbitrary sort based on icon name
            return servlet.toIcon(fai.file.getName());
        }
    }

    private static class FAIPriorityComparator extends FAISort {

        public FAIPriorityComparator(boolean rev) { super(rev); }

        /** highest first */
        public int compareIt(FileAndIndex l, FileAndIndex r) {
            return r.priority - l.priority;
        }
    }
}
