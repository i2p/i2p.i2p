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
package org.jrobin.core.jrrd;

import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;

import org.jrobin.core.RrdException;

/**
 * Instances of this class model an archive section of an RRD file.
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision$
 */
public class Archive {

	RRDatabase db;
	long offset;
	long dataOffset;
	long size;
	ConsolidationFunctionType type;
	int rowCount;
	int pdpCount;
	double xff;
	ArrayList<CDPStatusBlock> cdpStatusBlocks;
	int currentRow;

	private double[][] values;

	Archive(RRDatabase db) throws IOException,RrdException {

		this.db = db;

		RRDFile file = db.rrdFile;

		offset = file.getFilePointer();
		type =
				ConsolidationFunctionType.get(file.readString(Constants.CF_NAM_SIZE));
		rowCount = file.readInt();
		pdpCount = file.readInt();

		file.align();

		xff = file.readDouble();

		// Skip rest of rra_def_t.par[]
		file.align();
		file.skipBytes(72);

		size = file.getFilePointer() - offset;
	}

	/**
	 * Returns the type of function used to calculate the consolidated data point.
	 *
	 * @return the type of function used to calculate the consolidated data point.
	 */
	public ConsolidationFunctionType getType() {
		return type;
	}

	void loadCDPStatusBlocks(RRDFile file, int numBlocks) throws IOException, RrdException {

		cdpStatusBlocks = new ArrayList<CDPStatusBlock>();

		for (int i = 0; i < numBlocks; i++) {
			cdpStatusBlocks.add(new CDPStatusBlock(file));
		}
	}

	/**
	 * Returns the <code>CDPStatusBlock</code> at the specified position in this archive.
	 *
	 * @param index index of <code>CDPStatusBlock</code> to return.
	 * @return the <code>CDPStatusBlock</code> at the specified position in this archive.
	 */
	public CDPStatusBlock getCDPStatusBlock(int index) {
		return cdpStatusBlocks.get(index);
	}

	/**
	 * Returns an iterator over the CDP status blocks in this archive in proper sequence.
	 *
	 * @return an iterator over the CDP status blocks in this archive in proper sequence.
	 * @see CDPStatusBlock
	 */
	public Iterator<CDPStatusBlock> getCDPStatusBlocks() {
		return cdpStatusBlocks.iterator();
	}

	void loadCurrentRow(RRDFile file) throws IOException,RrdException {
		currentRow = file.readInt();
	}

	void loadData(RRDFile file, int dsCount) throws IOException {

		dataOffset = file.getFilePointer();

		// Skip over the data to position ourselves at the start of the next archive
		file.skipBytes(8 * rowCount * dsCount);
	}

	DataChunk loadData(DataChunk chunk) throws IOException,RrdException {

		Calendar end = Calendar.getInstance();
		Calendar start = (Calendar) end.clone();

		start.add(Calendar.DATE, -1);

		loadData(chunk, start.getTime().getTime() / 1000,
				end.getTime().getTime() / 1000);
		return chunk;
	}

	void loadData(DataChunk chunk, long startTime, long endTime)
			throws IOException,RrdException {

		long pointer;

		if (chunk.start < 0) {
			pointer = currentRow + 1;
		}
		else {
			pointer = currentRow + chunk.start + 1;
		}

		db.rrdFile.ras.seek(dataOffset + (pointer * 8));
		//cat.debug("Archive Base: " + dataOffset + " Archive Pointer: " + pointer);
		//cat.debug("Start Offset: " + chunk.start + " End Offset: "
		//          + (rowCount - chunk.end));

		double[][] data = chunk.data;

		/*
		 * This is also terrible - cleanup - CT
		 */
		int row = 0;
		for (int i = chunk.start; i < rowCount - chunk.end; i++, row++) {
			if (i < 0) {				   // no valid data yet
				for (int ii = 0; ii < chunk.dsCount; ii++) {
					data[row][ii] = Double.NaN;
				}
			}
			else if (i >= rowCount) {	// past valid data area
				for (int ii = 0; ii < chunk.dsCount; ii++) {
					data[row][ii] = Double.NaN;
				}
			}
			else {					   // inside the valid are but the pointer has to be wrapped
				if (pointer >= rowCount) {
					pointer -= rowCount;

					db.rrdFile.ras.seek(dataOffset + (pointer * 8));
				}

				for (int ii = 0; ii < chunk.dsCount; ii++) {
					data[row][ii] = db.rrdFile.readDouble();
				}

				pointer++;
			}
		}
	}

	void printInfo(PrintStream s, NumberFormat numberFormat, int index) {

		StringBuffer sb = new StringBuffer("rra[");

		sb.append(index);
		s.print(sb);
		s.print("].cf = \"");
		s.print(type);
		s.println("\"");
		s.print(sb);
		s.print("].rows = ");
		s.println(rowCount);
		s.print(sb);
		s.print("].pdp_per_row = ");
		s.println(pdpCount);
		s.print(sb);
		s.print("].xff = ");
		s.println(xff);
		sb.append("].cdp_prep[");

		int cdpIndex = 0;

		for (Iterator<CDPStatusBlock> i = cdpStatusBlocks.iterator(); i.hasNext();) {
			CDPStatusBlock cdp = i.next();

			s.print(sb);
			s.print(cdpIndex);
			s.print("].value = ");

			double value = cdp.value;

			s.println(Double.isNaN(value)
					? "NaN"
					: numberFormat.format(value));
			s.print(sb);
			s.print(cdpIndex++);
			s.print("].unknown_datapoints = ");
			s.println(cdp.unknownDatapoints);
		}
	}

	void toXml(PrintStream s) throws RrdException {

		try {
			s.println("\t<rra>");
			s.print("\t\t<cf> ");
			s.print(type);
			s.println(" </cf>");
			s.print("\t\t<pdp_per_row> ");
			s.print(pdpCount);
			s.print(" </pdp_per_row> <!-- ");
			s.print(db.header.pdpStep * pdpCount);
			s.println(" seconds -->");
			s.print("\t\t<xff> ");
			s.print(xff);
			s.println(" </xff>");
			s.println();
			s.println("\t\t<cdp_prep>");

			for (int i = 0; i < cdpStatusBlocks.size(); i++) {
				cdpStatusBlocks.get(i).toXml(s);
			}

			s.println("\t\t</cdp_prep>");
			s.println("\t\t<database>");

			long timer = -(rowCount - 1);
			int counter = 0;
			int row = currentRow;

			db.rrdFile.ras.seek(dataOffset + (row + 1) * 16);

			long lastUpdate = db.lastUpdate.getTime() / 1000;
			int pdpStep = db.header.pdpStep;
			NumberFormat numberFormat = new DecimalFormat("0.0000000000E0");
			SimpleDateFormat dateFormat =
					new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");

			while (counter++ < rowCount) {
				row++;

				if (row == rowCount) {
					row = 0;

					db.rrdFile.ras.seek(dataOffset);
				}

				long now = (lastUpdate - lastUpdate % (pdpCount * pdpStep))
						+ (timer * pdpCount * pdpStep);

				timer++;

				s.print("\t\t\t<!-- ");
				s.print(dateFormat.format(new Date(now * 1000)));
				s.print(" / ");
				s.print(now);
				s.print(" --> ");

				for (int col = 0; col < db.header.dsCount; col++) {
					s.print("<v> ");

					double value = db.rrdFile.readDouble();

					// NumberFormat doesn't know how to handle NaN
					if (Double.isNaN(value)) {
						s.print("NaN");
					}
					else {
						s.print(numberFormat.format(value));
					}

					s.print(" </v>");
				}

				s.println("</row>");
			}

			s.println("\t\t</database>");
			s.println("\t</rra>");
		}
		catch (IOException e) {	// Is the best thing to do here?
			throw new RuntimeException(e.getMessage());
		}
	}

	/*
	// THIS IS THE ORIGINAL CODE: BUGGY! Replaced by Sasa Markovic with a new method
	// Funny: the bug will appear only if dsCount != 2 :)
	public double[][] getValuesOriginal() throws IOException {
		if (values != null) {
			return values;
		}
		values = new double[db.header.dsCount][rowCount];
		int row = currentRow;
		db.rrdFile.ras.seek(dataOffset + (row + 1) * 16); // <----- BUG (resolved below)
		for (int counter = 0; counter < rowCount; counter++) {
			row++;
			if (row == rowCount) {
				row = 0;
				db.rrdFile.ras.seek(dataOffset);
			}
			for (int col = 0; col < db.header.dsCount; col++) {
				double value = db.rrdFile.readDouble();
				values[col][counter] = value;
			}
		}
		return values;
	}
    */

	// Resolved bug from the original method (see above)
	public double[][] getValues() throws IOException,RrdException {
		// OK PART
		if (values != null) {
			return values;
		}
		values = new double[db.header.dsCount][rowCount];
		int row = currentRow;
		// HERE ARE THE DRAGONS!
		db.rrdFile.ras.seek(dataOffset + (row + 1) * db.header.dsCount * 8);
		// OK, TOO!
		for (int counter = 0; counter < rowCount; counter++) {
			row++;
			if (row == rowCount) {
				row = 0;
				db.rrdFile.ras.seek(dataOffset);
			}
			for (int col = 0; col < db.header.dsCount; col++) {
				double value = db.rrdFile.readDouble();
				values[col][counter] = value;
			}
		}
		return values;
	}

	/**
	 * Returns the number of primary data points required for a consolidated
	 * data point in this archive.
	 *
	 * @return the number of primary data points required for a consolidated
	 *         data point in this archive.
	 */
	public int getPdpCount() {
		return pdpCount;
	}

	/**
	 * Returns the number of entries in this archive.
	 *
	 * @return the number of entries in this archive.
	 */
	public int getRowCount() {
		return rowCount;
	}

	/**
	 * Returns the X-Files Factor for this archive.
	 *
	 * @return the X-Files Factor for this archive.
	 */
	public double getXff() {
		return xff;
	}

	/**
	 * Returns a summary the contents of this archive.
	 *
	 * @return a summary of the information contained in this archive.
	 */
	public String toString() {

		StringBuffer sb = new StringBuffer("[Archive: OFFSET=0x");

		sb.append(Long.toHexString(offset));
		sb.append(", SIZE=0x");
		sb.append(Long.toHexString(size));
		sb.append(", type=");
		sb.append(type);
		sb.append(", rowCount=");
		sb.append(rowCount);
		sb.append(", pdpCount=");
		sb.append(pdpCount);
		sb.append(", xff=");
		sb.append(xff);
		sb.append(", currentRow=");
		sb.append(currentRow);
		sb.append("]");

		for (Iterator<CDPStatusBlock> i = cdpStatusBlocks.iterator(); i.hasNext();) {
			CDPStatusBlock cdp = i.next();

			sb.append("\n\t\t");
			sb.append(cdp.toString());
		}

		return sb.toString();
	}
}
