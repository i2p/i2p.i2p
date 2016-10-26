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
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

class TimeAxis implements RrdGraphConstants {
	private static final TimeAxisSetting[] tickSettings = {
			new TimeAxisSetting(0, SECOND, 30, MINUTE, 5, MINUTE, 5, 0, "HH:mm"),
			new TimeAxisSetting(2, MINUTE, 1, MINUTE, 5, MINUTE, 5, 0, "HH:mm"),
			new TimeAxisSetting(5, MINUTE, 2, MINUTE, 10, MINUTE, 10, 0, "HH:mm"),
			new TimeAxisSetting(10, MINUTE, 5, MINUTE, 20, MINUTE, 20, 0, "HH:mm"),
			new TimeAxisSetting(30, MINUTE, 10, HOUR, 1, HOUR, 1, 0, "HH:mm"),
			new TimeAxisSetting(60, MINUTE, 30, HOUR, 2, HOUR, 2, 0, "HH:mm"),
			new TimeAxisSetting(180, HOUR, 1, HOUR, 6, HOUR, 6, 0, "HH:mm"),
			new TimeAxisSetting(600, HOUR, 6, DAY, 1, DAY, 1, 24 * 3600, "EEE"),
			new TimeAxisSetting(1800, HOUR, 12, DAY, 1, DAY, 2, 24 * 3600, "EEE"),
			new TimeAxisSetting(3600, DAY, 1, WEEK, 1, WEEK, 1, 7 * 24 * 3600, "'Week 'w"),
			new TimeAxisSetting(3 * 3600, WEEK, 1, MONTH, 1, WEEK, 2, 7 * 24 * 3600, "'Week 'w"),
			new TimeAxisSetting(6 * 3600, MONTH, 1, MONTH, 1, MONTH, 1, 30 * 24 * 3600, "MMM"),
			new TimeAxisSetting(48 * 3600, MONTH, 1, MONTH, 3, MONTH, 3, 30 * 24 * 3600, "MMM"),
			new TimeAxisSetting(10 * 24 * 3600, YEAR, 1, YEAR, 1, YEAR, 1, 365 * 24 * 3600, "yy"),
			new TimeAxisSetting(-1, MONTH, 0, MONTH, 0, MONTH, 0, 0, "")
	};

	private TimeAxisSetting tickSetting;
	private RrdGraph rrdGraph;
	private double secPerPix;
	private Calendar calendar;

	TimeAxis(RrdGraph rrdGraph) {
		this.rrdGraph = rrdGraph;
		if (rrdGraph.im.xsize > 0) {
			this.secPerPix = (rrdGraph.im.end - rrdGraph.im.start) / Double.valueOf(rrdGraph.im.xsize);
		}
		this.calendar = Calendar.getInstance(Locale.getDefault());
		this.calendar.setFirstDayOfWeek(rrdGraph.gdef.firstDayOfWeek);
	}

	void draw() {
		chooseTickSettings();
		if (tickSetting == null) {
			return;
		}
		drawMinor();
		drawMajor();
		drawLabels();
	}

	private void drawMinor() {
		if (!rrdGraph.gdef.noMinorGrid) {
			adjustStartingTime(tickSetting.minorUnit, tickSetting.minorUnitCount);
			Paint color = rrdGraph.gdef.colors[COLOR_GRID];
			int y0 = rrdGraph.im.yorigin, y1 = y0 - rrdGraph.im.ysize;
			for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
				if (status == 0) {
					long time = calendar.getTime().getTime() / 1000L;
					int x = rrdGraph.mapper.xtr(time);
					rrdGraph.worker.drawLine(x, y0 - 1, x, y0 + 1, color, TICK_STROKE);
					rrdGraph.worker.drawLine(x, y0, x, y1, color, GRID_STROKE);
				}
				findNextTime(tickSetting.minorUnit, tickSetting.minorUnitCount);
			}
		}
	}

	private void drawMajor() {
		adjustStartingTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
		Paint color = rrdGraph.gdef.colors[COLOR_MGRID];
		int y0 = rrdGraph.im.yorigin, y1 = y0 - rrdGraph.im.ysize;
		for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
			if (status == 0) {
				long time = calendar.getTime().getTime() / 1000L;
				int x = rrdGraph.mapper.xtr(time);
				rrdGraph.worker.drawLine(x, y0 - 2, x, y0 + 2, color, TICK_STROKE);
				rrdGraph.worker.drawLine(x, y0, x, y1, color, GRID_STROKE);
			}
			findNextTime(tickSetting.majorUnit, tickSetting.majorUnitCount);
		}
	}

	private void drawLabels() {
		// escape strftime like format string
		String labelFormat = tickSetting.format.replaceAll("([^%]|^)%([^%t])", "$1%t$2");
		Font font = rrdGraph.gdef.getFont(FONTTAG_AXIS);
		Paint color = rrdGraph.gdef.colors[COLOR_FONT];
		adjustStartingTime(tickSetting.labelUnit, tickSetting.labelUnitCount);
		int y = rrdGraph.im.yorigin + (int) rrdGraph.worker.getFontHeight(font) + 2;
		for (int status = getTimeShift(); status <= 0; status = getTimeShift()) {
			String label = formatLabel(labelFormat, calendar.getTime());
			long time = calendar.getTime().getTime() / 1000L;
			int x1 = rrdGraph.mapper.xtr(time);
			int x2 = rrdGraph.mapper.xtr(time + tickSetting.labelSpan);
			int labelWidth = (int) rrdGraph.worker.getStringWidth(label, font);
			int x = x1 + (x2 - x1 - labelWidth) / 2;
			if (x >= rrdGraph.im.xorigin && x + labelWidth <= rrdGraph.im.xorigin + rrdGraph.im.xsize) {
				rrdGraph.worker.drawString(label, x, y, font, color);
			}
			findNextTime(tickSetting.labelUnit, tickSetting.labelUnitCount);
		}
	}

	private static String formatLabel(String format, Date date) {
		if (format.contains("%")) {
			// strftime like format string
			return String.format(format, date);
		}
		else {
			// simple date format
			return new SimpleDateFormat(format).format(date);
		}
	}

	private void findNextTime(int timeUnit, int timeUnitCount) {
		switch (timeUnit) {
			case SECOND:
				calendar.add(Calendar.SECOND, timeUnitCount);
				break;
			case MINUTE:
				calendar.add(Calendar.MINUTE, timeUnitCount);
				break;
			case HOUR:
				calendar.add(Calendar.HOUR_OF_DAY, timeUnitCount);
				break;
			case DAY:
				calendar.add(Calendar.DAY_OF_MONTH, timeUnitCount);
				break;
			case WEEK:
				calendar.add(Calendar.DAY_OF_MONTH, 7 * timeUnitCount);
				break;
			case MONTH:
				calendar.add(Calendar.MONTH, timeUnitCount);
				break;
			case YEAR:
				calendar.add(Calendar.YEAR, timeUnitCount);
				break;
		}
	}

	private int getTimeShift() {
		long time = calendar.getTime().getTime() / 1000L;
		return (time < rrdGraph.im.start) ? -1 : (time > rrdGraph.im.end) ? +1 : 0;
	}

	private void adjustStartingTime(int timeUnit, int timeUnitCount) {
		calendar.setTime(new Date(rrdGraph.im.start * 1000L));
		switch (timeUnit) {
			case SECOND:
				calendar.add(Calendar.SECOND, -(calendar.get(Calendar.SECOND) % timeUnitCount));
				break;
			case MINUTE:
				calendar.set(Calendar.SECOND, 0);
				calendar.add(Calendar.MINUTE, -(calendar.get(Calendar.MINUTE) % timeUnitCount));
				break;
			case HOUR:
				calendar.set(Calendar.SECOND, 0);
				calendar.set(Calendar.MINUTE, 0);
				calendar.add(Calendar.HOUR_OF_DAY, -(calendar.get(Calendar.HOUR_OF_DAY) % timeUnitCount));
				break;
			case DAY:
				calendar.set(Calendar.SECOND, 0);
				calendar.set(Calendar.MINUTE, 0);
				calendar.set(Calendar.HOUR_OF_DAY, 0);
				break;
			case WEEK:
				calendar.set(Calendar.SECOND, 0);
				calendar.set(Calendar.MINUTE, 0);
				calendar.set(Calendar.HOUR_OF_DAY, 0);
				int diffDays = calendar.get(Calendar.DAY_OF_WEEK) - calendar.getFirstDayOfWeek();
				if (diffDays < 0) {
					diffDays += 7;
				}
				calendar.add(Calendar.DAY_OF_MONTH, -diffDays);
				break;
			case MONTH:
				calendar.set(Calendar.SECOND, 0);
				calendar.set(Calendar.MINUTE, 0);
				calendar.set(Calendar.HOUR_OF_DAY, 0);
				calendar.set(Calendar.DAY_OF_MONTH, 1);
				calendar.add(Calendar.MONTH, -(calendar.get(Calendar.MONTH) % timeUnitCount));
				break;
			case YEAR:
				calendar.set(Calendar.SECOND, 0);
				calendar.set(Calendar.MINUTE, 0);
				calendar.set(Calendar.HOUR_OF_DAY, 0);
				calendar.set(Calendar.DAY_OF_MONTH, 1);
				calendar.set(Calendar.MONTH, 0);
				calendar.add(Calendar.YEAR, -(calendar.get(Calendar.YEAR) % timeUnitCount));
				break;
		}
	}


	private void chooseTickSettings() {
		if (rrdGraph.gdef.timeAxisSetting != null) {
			tickSetting = new TimeAxisSetting(rrdGraph.gdef.timeAxisSetting);
		}
		else {
			for (int i = 0; tickSettings[i].secPerPix >= 0 && secPerPix > tickSettings[i].secPerPix; i++) {
				tickSetting = tickSettings[i];
			}
		}
	}

}
