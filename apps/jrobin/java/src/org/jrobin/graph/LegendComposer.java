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

import java.util.ArrayList;
import java.util.List;

class LegendComposer implements RrdGraphConstants {
	private RrdGraphDef gdef;
	private ImageWorker worker;
	private int legX, legY, legWidth;
	private double interlegendSpace;
	private double leading;
	private double smallLeading;
	private double boxSpace;

	LegendComposer(RrdGraph rrdGraph, int legX, int legY, int legWidth) {
		this.gdef = rrdGraph.gdef;
		this.worker = rrdGraph.worker;
		this.legX = legX;
		this.legY = legY;
		this.legWidth = legWidth;
		interlegendSpace = rrdGraph.getInterlegendSpace();
		leading = rrdGraph.getLeading();
		smallLeading = rrdGraph.getSmallLeading();
		boxSpace = rrdGraph.getBoxSpace();
	}

	int placeComments() {
		Line line = new Line();
		for (CommentText comment : gdef.comments) {
			if (comment.isValidGraphElement()) {
				if (!line.canAccomodate(comment)) {
					line.layoutAndAdvance(false);
					line.clear();
				}
				line.add(comment);
			}
		}
		line.layoutAndAdvance(true);
		worker.dispose();
		return legY;
	}

	class Line {
		private String lastMarker;
		private double width;
		private int spaceCount;
		private boolean noJustification;
		private List<CommentText> comments = new ArrayList<CommentText>();

		Line() {
			clear();
		}

		void clear() {
			lastMarker = "";
			width = 0;
			spaceCount = 0;
			noJustification = false;
			comments.clear();
		}

		boolean canAccomodate(CommentText comment) {
			// always accommodate if empty
			if (comments.size() == 0) {
				return true;
			}
			// cannot accommodate if the last marker was \j, \l, \r, \c, \s
			if (lastMarker.equals(ALIGN_LEFT_MARKER) || lastMarker.equals(ALIGN_CENTER_MARKER) ||
					lastMarker.equals(ALIGN_RIGHT_MARKER) || lastMarker.equals(ALIGN_JUSTIFIED_MARKER) ||
					lastMarker.equals(VERTICAL_SPACING_MARKER)) {
				return false;
			}
			// cannot accommodate if line would be too long
			double commentWidth = getCommentWidth(comment);
			if (!lastMarker.equals(GLUE_MARKER)) {
				commentWidth += interlegendSpace;
			}
			return width + commentWidth <= legWidth;
		}

		void add(CommentText comment) {
			double commentWidth = getCommentWidth(comment);
			if (comments.size() > 0 && !lastMarker.equals(GLUE_MARKER)) {
				commentWidth += interlegendSpace;
				spaceCount++;
			}
			width += commentWidth;
			lastMarker = comment.marker;
			noJustification |= lastMarker.equals(NO_JUSTIFICATION_MARKER);
			comments.add(comment);
		}

		void layoutAndAdvance(boolean isLastLine) {
			if (comments.size() > 0) {
				if (lastMarker.equals(ALIGN_LEFT_MARKER)) {
					placeComments(legX, interlegendSpace);
				}
				else if (lastMarker.equals(ALIGN_RIGHT_MARKER)) {
					placeComments(legX + legWidth - width, interlegendSpace);
				}
				else if (lastMarker.equals(ALIGN_CENTER_MARKER)) {
					placeComments(legX + (legWidth - width) / 2.0, interlegendSpace);
				}
				else if (lastMarker.equals(ALIGN_JUSTIFIED_MARKER)) {
					// anything to justify?
					if (spaceCount > 0) {
						placeComments(legX, (legWidth - width) / spaceCount + interlegendSpace);
					}
					else {
						placeComments(legX, interlegendSpace);
					}
				}
				else if (lastMarker.equals(VERTICAL_SPACING_MARKER)) {
					placeComments(legX, interlegendSpace);
				}
				else {
					// nothing specified, align with respect to '\J'
					if (noJustification || isLastLine) {
						placeComments(legX, interlegendSpace);
					}
					else {
						placeComments(legX, (legWidth - width) / spaceCount + interlegendSpace);
					}
				}
				if (lastMarker.equals(VERTICAL_SPACING_MARKER)) {
					legY += smallLeading;
				}
				else {
					legY += leading;
				}
			}
		}

		private double getCommentWidth(CommentText comment) {
			double commentWidth = worker.getStringWidth(comment.resolvedText, gdef.getFont(FONTTAG_LEGEND));
			if (comment instanceof LegendText) {
				commentWidth += boxSpace;
			}
			return commentWidth;
		}

		private void placeComments(double xStart, double space) {
			double x = xStart;
			for (CommentText comment : comments) {
				comment.x = (int) x;
				comment.y = legY;
				x += getCommentWidth(comment);
				if (!comment.marker.equals(GLUE_MARKER)) {
					x += space;
				}
			}
		}
	}
}
