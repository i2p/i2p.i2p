package org.rrd4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.rrd4j.ConsolFun;

/**
 * Class used to perform various complex operations on RRD files. Use an instance of the
 * RrdToolkit class to:
 *
 * <ul>
 * <li>add datasource to a RRD file.
 * <li>add archive to a RRD file.
 * <li>remove datasource from a RRD file.
 * <li>remove archive from a RRD file.
 * </ul>
 *
 * All these operations can be performed on the copy of the original RRD file, or on the
 * original file itself (with possible backup file creation).
 * <p>
 * <b><u>IMPORTANT</u></b>: NEVER use methods found in this class on 'live' RRD files
 * (files which are currently in use).
 *
 */
@SuppressWarnings("deprecation")
public class RrdToolkit {

    private static final String SOURCE_AND_DESTINATION_PATHS_ARE_THE_SAME = "Source and destination paths are the same";

    private RrdToolkit() {

    }

    /**
     * Creates a new RRD file with one more datasource in it. RRD file is created based on the
     * existing one (the original RRD file is not modified at all). All data from
     * the original RRD file is copied to the new one.
     *
     * @param sourcePath    path to a RRD file to import data from (will not be modified)
     * @param destPath      path to a new RRD file (will be created)
     * @param newDatasource Datasource definition to be added to the new RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void addDatasource(String sourcePath, String destPath, DsDef newDatasource)
            throws IOException {
        addDatasources(sourcePath, destPath, Collections.singleton(newDatasource));
    }

    /**
     * Creates a new RRD file with one more datasource in it. RRD file is created based on the
     * existing one (the original RRD file is not modified at all). All data from
     * the original RRD file is copied to the new one.
     *
     * @param sourcePath     path to a RRD file to import data from (will not be modified)
     * @param destPath       path to a new RRD file (will be created)
     * @param newDatasources Datasource definitions to be added to the new RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void addDatasources(String sourcePath, String destPath, Iterable<DsDef> newDatasources)
            throws IOException {
        if (Util.sameFilePath(sourcePath, destPath)) {
            throw new IllegalArgumentException(SOURCE_AND_DESTINATION_PATHS_ARE_THE_SAME);
        }
        try (RrdDb rrdSource = RrdDb.getBuilder().setPath(sourcePath).build()) {
            RrdDef rrdDef = rrdSource.getRrdDef();
            rrdDef.setPath(destPath);
            for (DsDef newDatasource : newDatasources) {
                rrdDef.addDatasource(newDatasource);
            }
            try (RrdDb rrdDest = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
                rrdSource.copyStateTo(rrdDest);
            }
        }
    }

    /**
     * <p>Adds one more datasource to a RRD file.</p>
     * <p>WARNING: This method is potentially dangerous! It will modify your RRD file.
     * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
     * should be set to <code>true</code>). The backup file will be created in the same
     * directory as the original one with <code>.bak</code> extension added to the
     * original name.</p>
     * <p>Before applying this method, be sure that the specified RRD file is not in use
     * (not open)</p>
     *
     * @param sourcePath    path to a RRD file to add datasource to.
     * @param newDatasource Datasource definition to be added to the RRD file
     * @param saveBackup    true, if backup of the original file should be created;
     *                      false, otherwise
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void addDatasource(String sourcePath, DsDef newDatasource, boolean saveBackup) throws IOException {
        addDatasources(sourcePath, Collections.singleton(newDatasource), saveBackup);
    }

    /**
     * <p>Adds datasources to a RRD file.</p>
     * <p>WARNING: This method is potentially dangerous! It will modify your RRD file.
     * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
     * should be set to <code>true</code>). The backup file will be created in the same
     * directory as the original one with <code>.bak</code> extension added to the
     * original name.</p>
     * <p>Before applying this method, be sure that the specified RRD file is not in use
     * (not open)</p>
     *
     * @param sourcePath     path to a RRD file to add datasource to.
     * @param newDatasources Datasource definitions to be added to the RRD file
     * @param saveBackup    true, if backup of the original file should be created;
     *                      false, otherwise
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void addDatasources(String sourcePath, Iterable<DsDef> newDatasources, boolean saveBackup) throws IOException {
        String destPath = Util.getTmpFilename();
        addDatasources(sourcePath, destPath, newDatasources);
        copyFile(destPath, sourcePath, saveBackup);
    }

    /**
     * Creates a new RRD file with one datasource removed. RRD file is created based on the
     * existing one (the original RRD file is not modified at all). All remaining data from
     * the original RRD file is copied to the new one.
     *
     * @param sourcePath path to a RRD file to import data from (will not be modified)
     * @param destPath   path to a new RRD file (will be created)
     * @param dsName     Name of the Datasource to be removed from the new RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void removeDatasource(String sourcePath, String destPath, String dsName)
            throws IOException {
        if (Util.sameFilePath(sourcePath, destPath)) {
            throw new IllegalArgumentException(SOURCE_AND_DESTINATION_PATHS_ARE_THE_SAME);
        }

        try (RrdDb rrdSource = RrdDb.getBuilder().setPath(sourcePath).build()) {
            RrdDef rrdDef = rrdSource.getRrdDef();
            rrdDef.setPath(destPath);
            rrdDef.removeDatasource(dsName);
            try (RrdDb rrdDest = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
                rrdSource.copyStateTo(rrdDest);
            }
        }
    }

    /**
     * <p>Removes single datasource from a RRD file.</p>
     * <p>WARNING: This method is potentially dangerous! It will modify your RRD file.
     * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
     * should be set to <code>true</code>). The backup file will be created in the same
     * directory as the original one with <code>.bak</code> extension added to the
     * original name.</p>
     * <p>Before applying this method, be sure that the specified RRD file is not in use
     * (not open)</p>
     *
     * @param sourcePath path to a RRD file to remove datasource from.
     * @param dsName     Name of the Datasource to be removed from the RRD file
     * @param saveBackup true, if backup of the original file should be created;
     *                   false, otherwise
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void removeDatasource(String sourcePath, String dsName, boolean saveBackup) throws IOException {
        String destPath = Util.getTmpFilename();
        removeDatasource(sourcePath, destPath, dsName);
        copyFile(destPath, sourcePath, saveBackup);
    }

    /**
     * Renames single datasource in the given RRD file.
     *
     * @param sourcePath Path to a RRD file
     * @param oldDsName  Old datasource name
     * @param newDsName  New datasource name
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void renameDatasource(String sourcePath, String oldDsName, String newDsName) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            if (rrd.containsDs(oldDsName)) {
                Datasource datasource = rrd.getDatasource(oldDsName);
                datasource.setDsName(newDsName);
            } else {
                throw new IllegalArgumentException("Could not find datasource [" + oldDsName + "] in file " + sourcePath);
            }
        }
    }

    /**
     * Updates single or all datasource names in the specified RRD file
     * by appending '!' (if not already present). Datasources with names ending with '!'
     * will never store NaNs in RRA archives (zero value will be used instead). Might be useful
     * from time to time
     *
     * @param sourcePath Path to a RRD file
     * @param dsName     Datasource name or null if you want to rename all datasources
     * @return Number of datasources successfully renamed
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static int forceZerosForNans(String sourcePath, String dsName) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Datasource[] datasources;
            if (dsName == null) {
                datasources = rrd.getDatasources();
            } else {
                if (rrd.containsDs(dsName)) {
                    datasources = new Datasource[]{rrd.getDatasource(dsName)};
                } else {
                    throw new IllegalArgumentException("Could not find datasource [" + dsName + "] in file " + sourcePath);
                }
            }
            int count = 0;
            for (Datasource datasource : datasources) {
                String currentDsName = datasource.getName();
                if (!currentDsName.endsWith(DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX)) {
                    datasource.setDsName(currentDsName + DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX);
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Creates a new RRD file with one more archive in it. RRD file is created based on the
     * existing one (the original RRD file is not modified at all). All data from
     * the original RRD file is copied to the new one.
     *
     * @param sourcePath path to a RRD file to import data from (will not be modified)
     * @param destPath   path to a new RRD file (will be created)
     * @param newArchive Archive definition to be added to the new RRD file
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void addArchive(String sourcePath, String destPath, ArcDef newArchive) throws IOException {
        if (Util.sameFilePath(sourcePath, destPath)) {
            throw new IllegalArgumentException(SOURCE_AND_DESTINATION_PATHS_ARE_THE_SAME);
        }
        try (RrdDb rrdSource = RrdDb.getBuilder().setPath(sourcePath).build()) {
            RrdDef rrdDef = rrdSource.getRrdDef();
            rrdDef.setPath(destPath);
            rrdDef.addArchive(newArchive);
            try (RrdDb rrdDest = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
                rrdSource.copyStateTo(rrdDest);
            }
        }
    }

    /**
     * <p>Adds one more archive to a RRD file.</p>
     * <p>WARNING: This method is potentially dangerous! It will modify your RRD file.
     * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
     * should be set to <code>true</code>). The backup file will be created in the same
     * directory as the original one with <code>.bak</code> extension added to the
     * original name.</p>
     * <p>Before applying this method, be sure that the specified RRD file is not in use
     * (not open)</p>
     *
     * @param sourcePath path to a RRD file to add datasource to.
     * @param newArchive Archive definition to be added to the RRD file
     * @param saveBackup true, if backup of the original file should be created;
     *                   false, otherwise
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void addArchive(String sourcePath, ArcDef newArchive, boolean saveBackup) throws IOException {
        String destPath = Util.getTmpFilename();
        addArchive(sourcePath, destPath, newArchive);
        copyFile(destPath, sourcePath, saveBackup);
    }

    /**
     * Creates a new RRD file with one archive removed. RRD file is created based on the
     * existing one (the original RRD file is not modified at all). All relevant data from
     * the original RRD file is copied to the new one.
     *
     * @param sourcePath path to a RRD file to import data from (will not be modified)
     * @param destPath   path to a new RRD file (will be created)
     * @param consolFun  Consolidation function of Archive which should be removed
     * @param steps      Number of steps for Archive which should be removed
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void removeArchive(String sourcePath, String destPath, ConsolFun consolFun, int steps) throws IOException {
        if (Util.sameFilePath(sourcePath, destPath)) {
            throw new IllegalArgumentException(SOURCE_AND_DESTINATION_PATHS_ARE_THE_SAME);
        }

        try (RrdDb rrdSource = RrdDb.getBuilder().setPath(sourcePath).build()) {
            RrdDef rrdDef = rrdSource.getRrdDef();
            rrdDef.setPath(destPath);
            rrdDef.removeArchive(consolFun, steps);
            try (RrdDb rrdDest = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
                rrdSource.copyStateTo(rrdDest);
            }
        }
    }

    /**
     * <p>Removes one archive from a RRD file.</p>
     * <p>WARNING: This method is potentially dangerous! It will modify your RRD file.
     * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
     * should be set to <code>true</code>). The backup file will be created in the same
     * directory as the original one with <code>.bak</code> extension added to the
     * original name.</p>
     * <p>Before applying this method, be sure that the specified RRD file is not in use
     * (not open)</p>
     *
     * @param sourcePath path to a RRD file to add datasource to.
     * @param consolFun  Consolidation function of Archive which should be removed
     * @param steps      Number of steps for Archive which should be removed
     * @param saveBackup true, if backup of the original file should be created;
     *                   false, otherwise
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void removeArchive(String sourcePath, ConsolFun consolFun, int steps, boolean saveBackup) throws IOException {
        String destPath = Util.getTmpFilename();
        removeArchive(sourcePath, destPath, consolFun, steps);
        copyFile(destPath, sourcePath, saveBackup);
    }

    private static void copyFile(String sourcePath, String destPath, boolean saveBackup)
            throws IOException {
        
        Path source = Paths.get(sourcePath);
        Path destination = Paths.get(destPath);
        if (saveBackup) {
            String backupPath = getBackupPath(destPath);
            Files.move(destination, Paths.get(backupPath), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String getBackupPath(String destPath) {
        StringBuilder backupPath = new StringBuilder(destPath);
        do {
            backupPath.append( ".bak");
        }
        while (Util.fileExists(backupPath.toString()));
        return backupPath.toString();
    }

    /**
     * Sets datasource heartbeat to a new value.
     *
     * @param sourcePath     Path to existing RRD file (will be updated)
     * @param datasourceName Name of the datasource in the specified RRD file
     * @param newHeartbeat   New datasource heartbeat
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void setDsHeartbeat(String sourcePath, String datasourceName, long newHeartbeat) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Datasource ds = rrd.getDatasource(datasourceName);
            ds.setHeartbeat(newHeartbeat);
        }
    }

    /**
     * Sets datasource heartbeat to a new value.
     *
     * @param sourcePath   Path to existing RRD file (will be updated)
     * @param dsIndex      Index of the datasource in the specified RRD file
     * @param newHeartbeat New datasource heartbeat
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void setDsHeartbeat(String sourcePath, int dsIndex, long newHeartbeat) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Datasource ds = rrd.getDatasource(dsIndex);
            ds.setHeartbeat(newHeartbeat);
        }
    }

    /**
     * Sets datasource min value to a new value
     *
     * @param sourcePath           Path to existing RRD file (will be updated)
     * @param datasourceName       Name of the datasource in the specified RRD file
     * @param newMinValue          New min value for the datasource
     * @param filterArchivedValues set to <code>true</code> if archived values less than
     *                             <code>newMinValue</code> should be set to NaN; set to false, otherwise.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void setDsMinValue(String sourcePath, String datasourceName,
            double newMinValue, boolean filterArchivedValues) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Datasource ds = rrd.getDatasource(datasourceName);
            ds.setMinValue(newMinValue, filterArchivedValues);
        }
    }

    /**
     * Sets datasource max value to a new value.
     *
     * @param sourcePath           Path to existing RRD file (will be updated)
     * @param datasourceName       Name of the datasource in the specified RRD file
     * @param newMaxValue          New max value for the datasource
     * @param filterArchivedValues set to <code>true</code> if archived values greater than
     *                             <code>newMaxValue</code> should be set to NaN; set to false, otherwise.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void setDsMaxValue(String sourcePath, String datasourceName,
            double newMaxValue, boolean filterArchivedValues) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Datasource ds = rrd.getDatasource(datasourceName);
            ds.setMaxValue(newMaxValue, filterArchivedValues);
        }
    }

    /**
     * Updates valid value range for the given datasource.
     *
     * @param sourcePath           Path to existing RRD file (will be updated)
     * @param datasourceName       Name of the datasource in the specified RRD file
     * @param newMinValue          New min value for the datasource
     * @param newMaxValue          New max value for the datasource
     * @param filterArchivedValues set to <code>true</code> if archived values outside
     *                             of the specified min/max range should be replaced with NaNs.
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void setDsMinMaxValue(String sourcePath, String datasourceName,
            double newMinValue, double newMaxValue, boolean filterArchivedValues)
                    throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Datasource ds = rrd.getDatasource(datasourceName);
            ds.setMinMaxValue(newMinValue, newMaxValue, filterArchivedValues);
        }
    }

    /**
     * Sets single archive's X-files factor to a new value.
     *
     * @param sourcePath Path to existing RRD file (will be updated)
     * @param consolFun  Consolidation function of the target archive
     * @param steps      Number of steps of the target archive
     * @param newXff     New X-files factor for the target archive
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void setArcXff(String sourcePath, ConsolFun consolFun, int steps,
            double newXff) throws IOException {
        try (RrdDb rrd = RrdDb.getBuilder().setPath(sourcePath).build()) {
            Archive arc = rrd.getArchive(consolFun, steps);
            arc.setXff(newXff);
        }
    }

    /**
     * Creates new RRD file based on the existing one, but with a different
     * size (number of rows) for a single archive. The archive to be resized
     * is identified by its consolidation function and the number of steps.
     *
     * @param sourcePath Path to the source RRD file (will not be modified)
     * @param destPath   Path to the new RRD file (will be created)
     * @param consolFun  Consolidation function of the archive to be resized
     * @param numSteps   Number of steps of the archive to be resized
     * @param newRows    New archive size (number of archive rows)
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void resizeArchive(String sourcePath, String destPath, ConsolFun consolFun,
            int numSteps, int newRows) throws IOException {
        if (Util.sameFilePath(sourcePath, destPath)) {
            throw new IllegalArgumentException(SOURCE_AND_DESTINATION_PATHS_ARE_THE_SAME);
        }
        if (newRows < 2) {
            throw new IllegalArgumentException("New archive size must be at least 2");
        }

        try (RrdDb rrdSource = RrdDb.getBuilder().setPath(sourcePath).build()) {
            RrdDef rrdDef = rrdSource.getRrdDef();
            ArcDef arcDef = rrdDef.findArchive(consolFun, numSteps);
            if (arcDef.getRows() != newRows) {
                arcDef.setRows(newRows);
                rrdDef.setPath(destPath);
                RrdDb rrdDest = new RrdDb(rrdDef);
                try {
                    rrdSource.copyStateTo(rrdDest);
                } finally {
                    rrdDest.close();
                }
            }
        }
    }

    /**
     * Modifies existing RRD file, by resizing its chosen archive. The archive to be resized
     * is identified by its consolidation function and the number of steps.
     *
     * @param sourcePath Path to the RRD file (will be modified)
     * @param consolFun  Consolidation function of the archive to be resized
     * @param numSteps   Number of steps of the archive to be resized
     * @param newRows    New archive size (number of archive rows)
     * @param saveBackup true, if backup of the original file should be created;
     *                   false, otherwise
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void resizeArchive(String sourcePath, ConsolFun consolFun,
            int numSteps, int newRows, boolean saveBackup) throws IOException {
        String destPath = Util.getTmpFilename();
        resizeArchive(sourcePath, destPath, consolFun, numSteps, newRows);
        copyFile(destPath, sourcePath, saveBackup);
    }

    /**
     * Splits single RRD file with several datasources into a number of smaller RRD files
     * with a single datasource in it. All archived values are preserved. If
     * you have a RRD file named 'traffic.rrd' with two datasources, 'in' and 'out', this
     * method will create two files (with a single datasource, in the same directory)
     * named 'in-traffic.rrd' and 'out-traffic.rrd'.
     *
     * @param sourcePath Path to a RRD file with multiple datasources defined
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static void split(String sourcePath) throws IOException {
        try (RrdDb rrdSource = RrdDb.getBuilder().setPath(sourcePath).build()) {
            String[] dsNames = rrdSource.getDsNames();
            for (String dsName : dsNames) {
                RrdDef rrdDef = rrdSource.getRrdDef();
                rrdDef.setPath(createSplitPath(dsName, sourcePath));
                rrdDef.saveSingleDatasource(dsName);
                try (RrdDb rrdDest = RrdDb.getBuilder().setRrdDef(rrdDef).build()) {
                    rrdSource.copyStateTo(rrdDest);
                }
            }
        }
    }

    /**
     * Returns list of canonical file names with the specified extension in the given directory. This
     * method is not RRD related, but might come handy to create a quick list of all RRD files
     * in the given directory.
     *
     * @param directory Source directory
     * @param extension File extension (like ".rrd", ".jrb", ".rrd.jrb")
     * @param resursive true if all subdirectories should be traversed for the same extension, false otherwise
     * @return Array of sorted canonical file names with the given extension
     * @throws java.io.IOException Thrown in case of I/O error
     */
    public static String[] getCanonicalPaths(String directory, final String extension, boolean resursive)
            throws IOException {
        File baseDir = new File(directory);
        if (!baseDir.isDirectory()) {
            throw new RrdException("Not a directory: " + directory);
        }
        List<String> fileList = new LinkedList<>();
        traverseDirectory(new File(directory), extension, resursive, fileList);
        String[] result = fileList.toArray(new String[fileList.size()]);
        Arrays.sort(result);
        return result;
    }

    private static void traverseDirectory(File directory, String extension, boolean recursive, List<String> list)
            throws IOException {
        File[] files = directory.listFiles();
        for (File file : files) {
            if (file.isDirectory() && recursive) {
                // traverse subdirectories only if recursive flag is specified
                traverseDirectory(file, extension, recursive, list);
            } else if (file.isFile() && file.getName().endsWith(extension)) {
                list.add(file.getCanonicalPath());
            }
        }
    }

    private static String createSplitPath(String dsName, String sourcePath) {
        File file = new File(sourcePath);
        String newName = dsName + "-" + file.getName();
        String path = file.getAbsolutePath();
        String parentDir = path.substring(0, 1 + path.lastIndexOf(Util.getFileSeparator()));
        return parentDir + newName;
    }

}

