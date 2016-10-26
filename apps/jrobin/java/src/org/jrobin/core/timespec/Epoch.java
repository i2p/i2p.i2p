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
package org.jrobin.core.timespec;

import org.jrobin.core.RrdException;
import org.jrobin.core.Util;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Small swing-based utility to convert timestamps (seconds since epoch) to readable dates and vice versa.
 * Supports at-style time specification (like "now-2d", "noon yesterday") and other human-readable
 * data formats:<p>
 * <ul>
 * <li>MM/dd/yy HH:mm:ss
 * <li>dd.MM.yy HH:mm:ss
 * <li>dd.MM.yy HH:mm:ss
 * <li>MM/dd/yy HH:mm
 * <li>dd.MM.yy HH:mm
 * <li>yy-MM-dd HH:mm
 * <li>MM/dd/yy
 * <li>dd.MM.yy
 * <li>yy-MM-dd
 * <li>HH:mm MM/dd/yy
 * <li>HH:mm dd.MM.yy
 * <li>HH:mm yy-MM-dd
 * <li>HH:mm:ss MM/dd/yy
 * <li>HH:mm:ss dd.MM.yy
 * <li>HH:mm:ss yy-MM-dd
 * </ul>
 * The current timestamp is displayed in the title bar :)<p>
 */
public class Epoch extends JFrame {
	private static final long serialVersionUID = 1L;
	private static final String[] supportedFormats = {
			"MM/dd/yy HH:mm:ss", "dd.MM.yy HH:mm:ss", "yy-MM-dd HH:mm:ss", "MM/dd/yy HH:mm",
			"dd.MM.yy HH:mm", "yy-MM-dd HH:mm", "MM/dd/yy", "dd.MM.yy", "yy-MM-dd", "HH:mm MM/dd/yy",
			"HH:mm dd.MM.yy", "HH:mm yy-MM-dd", "HH:mm:ss MM/dd/yy", "HH:mm:ss dd.MM.yy", "HH:mm:ss yy-MM-dd"
	};

	private static final SimpleDateFormat[] parsers = new SimpleDateFormat[supportedFormats.length];
	private static final String helpText;

	private Timer timer = new Timer(1000, new ActionListener() {
		public void actionPerformed(ActionEvent e) {
			showTimestamp();
		}
	});

	static {
		for (int i = 0; i < parsers.length; i++) {
			parsers[i] = new SimpleDateFormat(supportedFormats[i]);
			parsers[i].setLenient(true);
		}
		StringBuffer tooltipBuff = new StringBuffer("<html><b>Supported input formats:</b><br>");
		for (String supportedFormat : supportedFormats) {
			tooltipBuff.append(supportedFormat).append("<br>");
		}
		tooltipBuff.append("<b>AT-style time specification</b><br>");
		tooltipBuff.append("timestamp<br><br>");
		tooltipBuff.append("Copyright (C) 2003-2005 Sasa Markovic, All Rights Reserved</html>");
		helpText = tooltipBuff.toString();
	}

	private JLabel topLabel = new JLabel("Enter timestamp or readable date:");
	private JTextField inputField = new JTextField(25);
	private JButton convertButton = new JButton("Convert");
	private JButton helpButton = new JButton("Help");

	private static final SimpleDateFormat OUTPUT_DATE_FORMAT =
			new SimpleDateFormat("MM/dd/yy HH:mm:ss EEE");

	Epoch() {
		super("Epoch");
		constructUI();
		timer.start();
	}

	private void constructUI() {
		JPanel c = (JPanel) getContentPane();
		c.setLayout(new BorderLayout(3, 3));
		c.add(topLabel, BorderLayout.NORTH);
		c.add(inputField, BorderLayout.WEST);
		c.add(convertButton, BorderLayout.CENTER);
		convertButton.setToolTipText(helpText);
		convertButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				convert();
			}
		});
		c.add(helpButton, BorderLayout.EAST);
		helpButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				JOptionPane.showMessageDialog(helpButton, helpText, "Epoch Help", JOptionPane.INFORMATION_MESSAGE);
			}
		});
		inputField.requestFocus();
		getRootPane().setDefaultButton(convertButton);
		setResizable(false);
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		pack();
		centerOnScreen();
		setVisible(true);
	}

	void centerOnScreen() {
		Toolkit t = Toolkit.getDefaultToolkit();
		Dimension screenSize = t.getScreenSize();
		Dimension frameSize = getPreferredSize();
		double x = (screenSize.getWidth() - frameSize.getWidth()) / 2;
		double y = (screenSize.getHeight() - frameSize.getHeight()) / 2;
		setLocation((int) x, (int) y);
	}

	private void convert() {
		String time = inputField.getText().trim();
		if (time.length() > 0) {
			// try simple timestamp
			try {
				long timestamp = Long.parseLong(time);
				Date date = new Date(timestamp * 1000L);
				formatDate(date);
			}
			catch (NumberFormatException nfe) {
				// failed, try as a date
				try {
					inputField.setText("" + parseDate(time));
				}
				catch (RrdException e) {
					inputField.setText("Could not convert, sorry");
				}
			}
		}
	}

	private void showTimestamp() {
		long timestamp = Util.getTime();
		setTitle(timestamp + " seconds since epoch");
	}

	void formatDate(Date date) {
		inputField.setText(OUTPUT_DATE_FORMAT.format(date));
	}

	private long parseDate(String time) throws RrdException {
		for (SimpleDateFormat parser : parsers) {
			try {
				return Util.getTimestamp(parser.parse(time));
			}
			catch (ParseException e) {
				// NOP
			}
		}
		return new TimeParser(time).parse().getTimestamp();
	}

	/**
	 * Main method which runs this utility.
	 *
	 * @param args Not used.
	 */
	public static void main(String[] args) {
		new Epoch();
	}
}
