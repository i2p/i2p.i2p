/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

package org.jrobin.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.LinkedList;
import java.util.Arrays;

/**
 * Class used to perform various complex operations on RRD files. Use an instance of the
 * RrdToolkit class to:
 * <p>
 * <ul>
 * <li>add datasource to a RRD file.
 * <li>add archive to a RRD file.
 * <li>remove datasource from a RRD file.
 * <li>remove archive from a RRD file.
 * </ul>
 * All these operations can be performed on the copy of the original RRD file, or on the
 * original file itself (with possible backup file creation).
 * <p>
 * <b><u>IMPORTANT</u></b>: NEVER use methods found in this class on 'live' RRD files
 * (files which are currently in use).
 */
public class RrdToolkit {
	/**
	 * Creates a new RRD file with one more datasource in it. RRD file is created based on the
	 * existing one (the original RRD file is not modified at all). All data from
	 * the original RRD file is copied to the new one.
	 *
	 * @param sourcePath	path to a RRD file to import data from (will not be modified)
	 * @param destPath	  path to a new RRD file (will be created)
	 * @param newDatasource Datasource definition to be added to the new RRD file
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void addDatasource(String sourcePath, String destPath, DsDef newDatasource)
			throws IOException, RrdException {
		if (Util.sameFilePath(sourcePath, destPath)) {
			throw new RrdException("Source and destination paths are the same");
		}
		RrdDb rrdSource = new RrdDb(sourcePath);
		try {
			RrdDef rrdDef = rrdSource.getRrdDef();
			rrdDef.setPath(destPath);
			rrdDef.addDatasource(newDatasource);
			RrdDb rrdDest = new RrdDb(rrdDef);
			try {
				rrdSource.copyStateTo(rrdDest);
			}
			finally {
				rrdDest.close();
			}
		}
		finally {
			rrdSource.close();
		}
	}

	/**
	 * <p>Adds one more datasource to a RRD file.</p>
	 * <p>WARNING: This method is potentialy dangerous! It will modify your RRD file.
	 * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
	 * should be set to <code>true</code>). The backup file will be created in the same
	 * directory as the original one with <code>.bak</code> extension added to the
	 * original name.</p>
	 * <p>Before applying this method, be sure that the specified RRD file is not in use
	 * (not open)</p>
	 *
	 * @param sourcePath	path to a RRD file to add datasource to.
	 * @param newDatasource Datasource definition to be added to the RRD file
	 * @param saveBackup	true, if backup of the original file should be created;
	 *                      false, otherwise
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void addDatasource(String sourcePath, DsDef newDatasource, boolean saveBackup)
			throws IOException, RrdException {
		String destPath = Util.getTmpFilename();
		addDatasource(sourcePath, destPath, newDatasource);
		copyFile(destPath, sourcePath, saveBackup);
	}

	/**
	 * Creates a new RRD file with one datasource removed. RRD file is created based on the
	 * existing one (the original RRD file is not modified at all). All remaining data from
	 * the original RRD file is copied to the new one.
	 *
	 * @param sourcePath path to a RRD file to import data from (will not be modified)
	 * @param destPath   path to a new RRD file (will be created)
	 * @param dsName	 Name of the Datasource to be removed from the new RRD file
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void removeDatasource(String sourcePath, String destPath, String dsName)
			throws IOException, RrdException {
		if (Util.sameFilePath(sourcePath, destPath)) {
			throw new RrdException("Source and destination paths are the same");
		}
		RrdDb rrdSource = new RrdDb(sourcePath);
		try {
			RrdDef rrdDef = rrdSource.getRrdDef();
			rrdDef.setPath(destPath);
			rrdDef.removeDatasource(dsName);
			RrdDb rrdDest = new RrdDb(rrdDef);
			try {
				rrdSource.copyStateTo(rrdDest);
			}
			finally {
				rrdDest.close();
			}
		}
		finally {
			rrdSource.close();
		}
	}

	/**
	 * <p>Removes single datasource from a RRD file.</p>
	 * <p>WARNING: This method is potentialy dangerous! It will modify your RRD file.
	 * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
	 * should be set to <code>true</code>). The backup file will be created in the same
	 * directory as the original one with <code>.bak</code> extension added to the
	 * original name.</p>
	 * <p>Before applying this method, be sure that the specified RRD file is not in use
	 * (not open)</p>
	 *
	 * @param sourcePath path to a RRD file to remove datasource from.
	 * @param dsName	 Name of the Datasource to be removed from the RRD file
	 * @param saveBackup true, if backup of the original file should be created;
	 *                   false, otherwise
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void removeDatasource(String sourcePath, String dsName, boolean saveBackup)
			throws IOException, RrdException {
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
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error (invalid path or datasource names,
	 *                      for example)
	 */
	public static void renameDatasource(String sourcePath, String oldDsName, String newDsName)
			throws IOException, RrdException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			if (rrd.containsDs(oldDsName)) {
				Datasource datasource = rrd.getDatasource(oldDsName);
				datasource.setDsName(newDsName);
			}
			else {
				throw new RrdException("Could not find datasource [" + oldDsName + "] in file " + sourcePath);
			}
		}
		finally {
			rrd.close();
		}
	}

	/**
	 * Updates single or all datasource names in the specified RRD file
	 * by appending '!' (if not already present). Datasources with names ending with '!'
	 * will never store NaNs in RRA archives (zero value will be used instead). Might be useful
	 * from time to time
	 *
	 * @param sourcePath Path to a RRD file
	 * @param dsName	 Datasource name or null if you want to rename all datasources
	 * @return Number of datasources successfully renamed
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error (invalid path or datasource name,
	 *                      for example)
	 */
	public static int forceZerosForNans(String sourcePath, String dsName) throws IOException, RrdException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Datasource[] datasources;
			if (dsName == null) {
				datasources = rrd.getDatasources();
			}
			else {
				if (rrd.containsDs(dsName)) {
					datasources = new Datasource[] {rrd.getDatasource(dsName)};
				}
				else {
					throw new RrdException("Could not find datasource [" + dsName + "] in file " + sourcePath);
				}
			}
			int count = 0;
			for (Datasource datasource : datasources) {
				String currentDsName = datasource.getDsName();
				if (!currentDsName.endsWith(DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX)) {
					datasource.setDsName(currentDsName + DsDef.FORCE_ZEROS_FOR_NANS_SUFFIX);
					count++;
				}
			}
			return count;
		}
		finally {
			rrd.close();
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
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void addArchive(String sourcePath, String destPath, ArcDef newArchive)
			throws IOException, RrdException {
		if (Util.sameFilePath(sourcePath, destPath)) {
			throw new RrdException("Source and destination paths are the same");
		}
		RrdDb rrdSource = new RrdDb(sourcePath);
		try {
			RrdDef rrdDef = rrdSource.getRrdDef();
			rrdDef.setPath(destPath);
			rrdDef.addArchive(newArchive);
			RrdDb rrdDest = new RrdDb(rrdDef);
			try {
				rrdSource.copyStateTo(rrdDest);
			}
			finally {
				rrdDest.close();
			}
		}
		finally {
			rrdSource.close();
		}
	}

	/**
	 * <p>Adds one more archive to a RRD file.</p>
	 * <p>WARNING: This method is potentialy dangerous! It will modify your RRD file.
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
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void addArchive(String sourcePath, ArcDef newArchive, boolean saveBackup)
			throws IOException, RrdException {
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
	 * @param steps	  Number of steps for Archive which should be removed
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void removeArchive(String sourcePath, String destPath, String consolFun, int steps)
			throws IOException, RrdException {
		if (Util.sameFilePath(sourcePath, destPath)) {
			throw new RrdException("Source and destination paths are the same");
		}
		RrdDb rrdSource = new RrdDb(sourcePath);
		try {
			RrdDef rrdDef = rrdSource.getRrdDef();
			rrdDef.setPath(destPath);
			rrdDef.removeArchive(consolFun, steps);
			RrdDb rrdDest = new RrdDb(rrdDef);
			try {
				rrdSource.copyStateTo(rrdDest);
			}
			finally {
				rrdDest.close();
			}
		}
		finally {
			rrdSource.close();
		}
	}

	/**
	 * <p>Removes one archive from a RRD file.</p>
	 * <p>WARNING: This method is potentialy dangerous! It will modify your RRD file.
	 * It is highly recommended to preserve the original RRD file (<i>saveBackup</i>
	 * should be set to <code>true</code>). The backup file will be created in the same
	 * directory as the original one with <code>.bak</code> extension added to the
	 * original name.</p>
	 * <p>Before applying this method, be sure that the specified RRD file is not in use
	 * (not open)</p>
	 *
	 * @param sourcePath path to a RRD file to add datasource to.
	 * @param consolFun  Consolidation function of Archive which should be removed
	 * @param steps	  Number of steps for Archive which should be removed
	 * @param saveBackup true, if backup of the original file should be created;
	 *                   false, otherwise
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void removeArchive(String sourcePath, String consolFun, int steps,
									 boolean saveBackup) throws IOException, RrdException {
		String destPath = Util.getTmpFilename();
		removeArchive(sourcePath, destPath, consolFun, steps);
		copyFile(destPath, sourcePath, saveBackup);
	}

	private static void copyFile(String sourcePath, String destPath, boolean saveBackup)
			throws IOException {
		File source = new File(sourcePath);
		File dest = new File(destPath);
		if (saveBackup) {
			String backupPath = getBackupPath(destPath);
			File backup = new File(backupPath);
			deleteFile(backup);
			if (!dest.renameTo(backup)) {
				throw new IOException("Could not create backup file " + backupPath);
			}
		}
		deleteFile(dest);
		if (!source.renameTo(dest)) {
			throw new IOException("Could not create file " + destPath + " from " + sourcePath);
		}
	}

	private static String getBackupPath(String destPath) {
		StringBuffer sb = new StringBuffer(destPath);
		do {
			sb.append(".bak");
		} while (Util.fileExists(sb.toString()));
		return sb.toString();
	}

	/**
	 * Sets datasource heartbeat to a new value.
	 *
	 * @param sourcePath	 Path to exisiting RRD file (will be updated)
	 * @param datasourceName Name of the datasource in the specified RRD file
	 * @param newHeartbeat   New datasource heartbeat
	 * @throws RrdException Thrown in case of JRobin specific error
	 * @throws IOException  Thrown in case of I/O error
	 */
	public static void setDsHeartbeat(String sourcePath, String datasourceName,
									  long newHeartbeat) throws RrdException, IOException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Datasource ds = rrd.getDatasource(datasourceName);
			ds.setHeartbeat(newHeartbeat);
		}
		finally {
			rrd.close();
		}
	}

	/**
	 * Sets datasource heartbeat to a new value.
	 *
	 * @param sourcePath   Path to exisiting RRD file (will be updated)
	 * @param dsIndex	  Index of the datasource in the specified RRD file
	 * @param newHeartbeat New datasource heartbeat
	 * @throws RrdException Thrown in case of JRobin specific error
	 * @throws IOException  Thrown in case of I/O error
	 */
	public static void setDsHeartbeat(String sourcePath, int dsIndex, long newHeartbeat)
			throws RrdException, IOException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Datasource ds = rrd.getDatasource(dsIndex);
			ds.setHeartbeat(newHeartbeat);
		}
		finally {
			rrd.close();
		}
	}

	/**
	 * Sets datasource min value to a new value
	 *
	 * @param sourcePath		   Path to exisiting RRD file (will be updated)
	 * @param datasourceName	   Name of the datasource in the specified RRD file
	 * @param newMinValue		  New min value for the datasource
	 * @param filterArchivedValues set to <code>true</code> if archived values less than
	 *                             <code>newMinValue</code> should be set to NaN; set to false, otherwise.
	 * @throws RrdException Thrown in case of JRobin specific error
	 * @throws IOException  Thrown in case of I/O error
	 */
	public static void setDsMinValue(String sourcePath, String datasourceName,
									 double newMinValue, boolean filterArchivedValues) throws RrdException, IOException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Datasource ds = rrd.getDatasource(datasourceName);
			ds.setMinValue(newMinValue, filterArchivedValues);
		}
		finally {
			rrd.close();
		}
	}

	/**
	 * Sets datasource max value to a new value.
	 *
	 * @param sourcePath		   Path to exisiting RRD file (will be updated)
	 * @param datasourceName	   Name of the datasource in the specified RRD file
	 * @param newMaxValue		  New max value for the datasource
	 * @param filterArchivedValues set to <code>true</code> if archived values greater than
	 *                             <code>newMaxValue</code> should be set to NaN; set to false, otherwise.
	 * @throws RrdException Thrown in case of JRobin specific error
	 * @throws IOException  Thrown in case of I/O error
	 */
	public static void setDsMaxValue(String sourcePath, String datasourceName,
									 double newMaxValue, boolean filterArchivedValues) throws RrdException, IOException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Datasource ds = rrd.getDatasource(datasourceName);
			ds.setMaxValue(newMaxValue, filterArchivedValues);
		}
		finally {
			rrd.close();
		}
	}

	/**
	 * Updates valid value range for the given datasource.
	 *
	 * @param sourcePath		   Path to exisiting RRD file (will be updated)
	 * @param datasourceName	   Name of the datasource in the specified RRD file
	 * @param newMinValue		  New min value for the datasource
	 * @param newMaxValue		  New max value for the datasource
	 * @param filterArchivedValues set to <code>true</code> if archived values outside
	 *                             of the specified min/max range should be replaced with NaNs.
	 * @throws RrdException Thrown in case of JRobin specific error
	 * @throws IOException  Thrown in case of I/O error
	 */
	public static void setDsMinMaxValue(String sourcePath, String datasourceName,
										double newMinValue, double newMaxValue, boolean filterArchivedValues)
			throws RrdException, IOException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Datasource ds = rrd.getDatasource(datasourceName);
			ds.setMinMaxValue(newMinValue, newMaxValue, filterArchivedValues);
		}
		finally {
			rrd.close();
		}
	}

	/**
	 * Sets single archive's X-files factor to a new value.
	 *
	 * @param sourcePath Path to existing RRD file (will be updated)
	 * @param consolFun  Consolidation function of the target archive
	 * @param steps	  Number of sptes of the target archive
	 * @param newXff	 New X-files factor for the target archive
	 * @throws RrdException Thrown in case of JRobin specific error
	 * @throws IOException  Thrown in case of I/O error
	 */
	public static void setArcXff(String sourcePath, String consolFun, int steps,
								 double newXff) throws RrdException, IOException {
		RrdDb rrd = new RrdDb(sourcePath);
		try {
			Archive arc = rrd.getArchive(consolFun, steps);
			arc.setXff(newXff);
		}
		finally {
			rrd.close();
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
	 * @param newRows	New archive size (number of archive rows)
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void resizeArchive(String sourcePath, String destPath, String consolFun,
									 int numSteps, int newRows)
			throws IOException, RrdException {
		if (Util.sameFilePath(sourcePath, destPath)) {
			throw new RrdException("Source and destination paths are the same");
		}
		if (newRows < 2) {
			throw new RrdException("New arcihve size must be at least 2");
		}
		RrdDb rrdSource = new RrdDb(sourcePath);
		try {
			RrdDef rrdDef = rrdSource.getRrdDef();
			ArcDef arcDef = rrdDef.findArchive(consolFun, numSteps);
			if (arcDef.getRows() != newRows) {
				arcDef.setRows(newRows);
				rrdDef.setPath(destPath);
				RrdDb rrdDest = new RrdDb(rrdDef);
				try {
					rrdSource.copyStateTo(rrdDest);
				}
				finally {
					rrdDest.close();
				}
			}
		}
		finally {
			rrdSource.close();
		}
	}

	/**
	 * Modifies existing RRD file, by resizing its chosen archive. The archive to be resized
	 * is identified by its consolidation function and the number of steps.
	 *
	 * @param sourcePath Path to the RRD file (will be modified)
	 * @param consolFun  Consolidation function of the archive to be resized
	 * @param numSteps   Number of steps of the archive to be resized
	 * @param newRows	New archive size (number of archive rows)
	 * @param saveBackup true, if backup of the original file should be created;
	 *                   false, otherwise
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void resizeArchive(String sourcePath, String consolFun,
									 int numSteps, int newRows, boolean saveBackup)
			throws IOException, RrdException {
		String destPath = Util.getTmpFilename();
		resizeArchive(sourcePath, destPath, consolFun, numSteps, newRows);
		copyFile(destPath, sourcePath, saveBackup);
	}

	private static void deleteFile(File file) throws IOException {
		if (file.exists() && !file.delete()) {
			throw new IOException("Could not delete file: " + file.getCanonicalPath());
		}
	}

	/**
	 * Splits single RRD file with several datasources into a number of smaller RRD files
	 * with a single datasource in it. All archived values are preserved. If
	 * you have a RRD file named 'traffic.rrd' with two datasources, 'in' and 'out', this
	 * method will create two files (with a single datasource, in the same directory)
	 * named 'in-traffic.rrd' and 'out-traffic.rrd'.
	 *
	 * @param sourcePath Path to a RRD file with multiple datasources defined
	 * @throws IOException  Thrown in case of I/O error
	 * @throws RrdException Thrown in case of JRobin specific error
	 */
	public static void split(String sourcePath) throws IOException, RrdException {
		RrdDb rrdSource = new RrdDb(sourcePath);
		try {
			String[] dsNames = rrdSource.getDsNames();
			for (String dsName : dsNames) {
				RrdDef rrdDef = rrdSource.getRrdDef();
				rrdDef.setPath(createSplitPath(dsName, sourcePath));
				rrdDef.saveSingleDatasource(dsName);
				RrdDb rrdDest = new RrdDb(rrdDef);
				try {
					rrdSource.copyStateTo(rrdDest);
				}
				finally {
					rrdDest.close();
				}
			}
		}
		finally {
			rrdSource.close();
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
	 * @throws IOException Thrown in case of I/O error
	 */
	public static String[] getCanonicalPaths(String directory, final String extension, boolean resursive)
			throws IOException {
		File baseDir = new File(directory);
		if (!baseDir.isDirectory()) {
			throw new IOException("Not a directory: " + directory);
		}
		List<String> fileList = new LinkedList<String>();
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
			}
			else if (file.isFile() && file.getName().endsWith(extension)) {
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
