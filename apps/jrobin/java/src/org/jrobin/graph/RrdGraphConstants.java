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
package org.jrobin.graph;

import java.awt.*;
import java.util.Calendar;
import java.util.Locale;

/**
 * Class to represent various constants used for graphing. No methods are specified.
 */
public interface RrdGraphConstants {
	/**
	 * Default graph starting time
	 */
	String DEFAULT_START = "end-1d";
	/**
	 * Default graph ending time
	 */
	String DEFAULT_END = "now";

	/**
	 * Constant to represent second
	 */
	int SECOND = Calendar.SECOND;
	/**
	 * Constant to represent minute
	 */
	int MINUTE = Calendar.MINUTE;
	/**
	 * Constant to represent hour
	 */
	int HOUR = Calendar.HOUR_OF_DAY;
	/**
	 * Constant to represent day
	 */
	int DAY = Calendar.DAY_OF_MONTH;
	/**
	 * Constant to represent week
	 */
	int WEEK = Calendar.WEEK_OF_YEAR;
	/**
	 * Constant to represent month
	 */
	int MONTH = Calendar.MONTH;
	/**
	 * Constant to represent year
	 */
	int YEAR = Calendar.YEAR;

	/**
	 * Constant to represent Monday
	 */
	int MONDAY = Calendar.MONDAY;
	/**
	 * Constant to represent Tuesday
	 */
	int TUESDAY = Calendar.TUESDAY;
	/**
	 * Constant to represent Wednesday
	 */
	int WEDNESDAY = Calendar.WEDNESDAY;
	/**
	 * Constant to represent Thursday
	 */
	int THURSDAY = Calendar.THURSDAY;
	/**
	 * Constant to represent Friday
	 */
	int FRIDAY = Calendar.FRIDAY;
	/**
	 * Constant to represent Saturday
	 */
	int SATURDAY = Calendar.SATURDAY;
	/**
	 * Constant to represent Sunday
	 */
	int SUNDAY = Calendar.SUNDAY;

	/**
	 * Index of the canvas color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_CANVAS = 0;
	/**
	 * Index of the background color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_BACK = 1;
	/**
	 * Index of the top-left graph shade color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_SHADEA = 2;
	/**
	 * Index of the bottom-right graph shade color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_SHADEB = 3;
	/**
	 * Index of the minor grid color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_GRID = 4;
	/**
	 * Index of the major grid color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_MGRID = 5;
	/**
	 * Index of the font color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_FONT = 6;
	/**
	 * Index of the frame color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_FRAME = 7;
	/**
	 * Index of the arrow color. Used in {@link RrdGraphDef#setColor(int, java.awt.Paint)}
	 */
	int COLOR_ARROW = 8;

	/**
	 * Allowed color names which can be used in {@link RrdGraphDef#setColor(String, java.awt.Paint)} method
	 */
	String[] COLOR_NAMES = {
			"canvas", "back", "shadea", "shadeb", "grid", "mgrid", "font", "frame", "arrow"
	};

	/**
	 * Default first day of the week (obtained from the default locale)
	 */
	int FIRST_DAY_OF_WEEK = Calendar.getInstance(Locale.getDefault()).getFirstDayOfWeek();

	/**
	 * Default graph canvas color
	 */
	Color DEFAULT_CANVAS_COLOR = Color.WHITE;
	/**
	 * Default graph background color
	 */
	Color DEFAULT_BACK_COLOR = new Color(245, 245, 245);
	/**
	 * Default top-left graph shade color
	 */
	Color DEFAULT_SHADEA_COLOR = new Color(200, 200, 200);
	/**
	 * Default bottom-right graph shade color
	 */
	Color DEFAULT_SHADEB_COLOR = new Color(150, 150, 150);
	/**
	 * Default minor grid color
	 */
	Color DEFAULT_GRID_COLOR = new Color(171, 171, 171, 95);
	// Color DEFAULT_GRID_COLOR = new Color(140, 140, 140);
	/**
	 * Default major grid color
	 */
	Color DEFAULT_MGRID_COLOR = new Color(255, 91, 91, 95);
	// Color DEFAULT_MGRID_COLOR = new Color(130, 30, 30);
	/**
	 * Default font color
	 */
	Color DEFAULT_FONT_COLOR = Color.BLACK;
	/**
	 * Default frame color
	 */
	Color DEFAULT_FRAME_COLOR = Color.BLACK;
	/**
	 * Default arrow color
	 */
	Color DEFAULT_ARROW_COLOR = Color.RED;

	/**
	 * Constant to represent left alignment marker
	 */
	String ALIGN_LEFT_MARKER = "\\l";
	/**
	 * Constant to represent centered alignment marker
	 */
	String ALIGN_CENTER_MARKER = "\\c";
	/**
	 * Constant to represent right alignment marker
	 */
	String ALIGN_RIGHT_MARKER = "\\r";
	/**
	 * Constant to represent justified alignment marker
	 */
	String ALIGN_JUSTIFIED_MARKER = "\\j";
	/**
	 * Constant to represent "glue" marker
	 */
	String GLUE_MARKER = "\\g";
	/**
	 * Constant to represent vertical spacing marker
	 */
	String VERTICAL_SPACING_MARKER = "\\s";
	/**
	 * Constant to represent no justification markers
	 */
	String NO_JUSTIFICATION_MARKER = "\\J";
	/**
	 * Used internally
	 */
	String[] MARKERS = {
			ALIGN_LEFT_MARKER, ALIGN_CENTER_MARKER, ALIGN_RIGHT_MARKER,
			ALIGN_JUSTIFIED_MARKER, GLUE_MARKER, VERTICAL_SPACING_MARKER, NO_JUSTIFICATION_MARKER
	};

	/**
	 * Constant to represent in-memory image name
	 */
	String IN_MEMORY_IMAGE = "-";

	/**
	 * Default units length
	 */
	int DEFAULT_UNITS_LENGTH = 9;
	/**
	 * Default graph width
	 */
	int DEFAULT_WIDTH = 400;
	/**
	 * Default graph height
	 */
	int DEFAULT_HEIGHT = 100;
	/**
	 * Default image format
	 */
	String DEFAULT_IMAGE_FORMAT = "gif";
	/**
	 * Default image quality, used only for jpeg graphs
	 */
	float DEFAULT_IMAGE_QUALITY = 0.8F; // only for jpegs, not used for png/gif
	/**
	 * Default value base
	 */
	double DEFAULT_BASE = 1000;

	/**
	 * Default font name, determined based on the current operating system
	 */
	String DEFAULT_FONT_NAME = System.getProperty("os.name").toLowerCase().contains("windows") ?
			"Lucida Sans Typewriter" : "Monospaced";

	/**
	 * Default graph small font
	 */
	String DEFAULT_MONOSPACE_FONT_FILE = "DejaVuSansMono.ttf";

	/**
	 * Default graph large font
	 */
	String DEFAULT_PROPORTIONAL_FONT_FILE = "DejaVuSans-Bold.ttf";

	/**
	 * Used internally
	 */
	double LEGEND_LEADING = 1.2; // chars
	/**
	 * Used internally
	 */
	double LEGEND_LEADING_SMALL = 0.7; // chars
	/**
	 * Used internally
	 */
	double LEGEND_BOX_SPACE = 1.2; // chars
	/**
	 * Used internally
	 */
	double LEGEND_BOX = 0.7; // chars
	/**
	 * Used internally
	 */
	int LEGEND_INTERSPACING = 2; // chars
	/**
	 * Used internally
	 */
	int PADDING_LEFT = 0; // pix - absent vertical label provides padding here
	/**
	 * Used internally
	 */
	int PADDING_TOP = 5; // pix -- additional top pixels added by frame border
	/**
	 * Used internally
	 */
	int PADDING_TITLE = 7; // pix
	/**
	 * Used internally
	 */
	int PADDING_RIGHT = 20; // pix
	/**
	 * Used internally
	 */
	double PADDING_PLOT = 1.7; //chars
	/**
	 * Used internally
	 */
	double PADDING_LEGEND = 2.1; // chars
	/**
	 * Used internally
	 */
	int PADDING_BOTTOM = 6; // pix
	/**
	 * Used internally
	 */
	int PADDING_VLABEL = 8; // pix

	/**
	 * Stroke used to draw grid
	 */
	// solid line
	//Stroke GRID_STROKE = new BasicStroke(1);

	// dotted line
	 Stroke GRID_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[] {1, 1}, 0);
	/**
	 * Stroke used to draw ticks
	 */
	Stroke TICK_STROKE = new BasicStroke(1);

	/**
	 * Index of the default font. Used in {@link RrdGraphDef#setFont(int, java.awt.Font)}
	 */
	int FONTTAG_DEFAULT   = 0;

	/**
	 * Index of the title font. Used in {@link RrdGraphDef#setFont(int, java.awt.Font)}
	 */
	int FONTTAG_TITLE     = 1;

	/**
	 * Index of the axis label font. Used in {@link RrdGraphDef#setFont(int, java.awt.Font)}
	 */
	int FONTTAG_AXIS      = 2;

	/**
	 * Index of the vertical unit label font. Used in {@link RrdGraphDef#setFont(int, java.awt.Font)}
	 */
	int FONTTAG_UNIT      = 3;

	/**
	 * Index of the graph legend font. Used in {@link RrdGraphDef#setFont(int, java.awt.Font)}
	 */
	int FONTTAG_LEGEND    = 4;

	/**
	 * Index of the edge watermark font. Used in {@link RrdGraphDef#setFont(int, java.awt.Font)}
	 */
	int FONTTAG_WATERMARK = 5;

	/**
	 * Allowed font tag names which can be used in {@link RrdGraphDef#setFont(String, java.awt.Font)} method
	 */
	String[] FONTTAG_NAMES = {
		"DEFAULT", "TITLE", "AXIS", "UNIT", "LEGEND", "WATERMARK"
	};
}
