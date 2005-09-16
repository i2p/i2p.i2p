/*
 	launch4j :: Cross-platform Java application wrapper for creating Windows native executables
 	Copyright (C) 2005 Grzegorz Kowal

	This program is free software; you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation; either version 2 of the License, or
	(at your option) any later version.

	This program is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with this program; if not, write to the Free Software
	Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/

/*
 * Created on 2005-05-09
 */
package net.sf.launch4j.formimpl;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JToolBar;
import javax.swing.UIManager;

import com.jgoodies.plaf.Options;
import com.jgoodies.plaf.plastic.PlasticXPLookAndFeel;

import foxtrot.Task;
import foxtrot.Worker;

import net.sf.launch4j.Builder;
import net.sf.launch4j.BuilderException;
import net.sf.launch4j.ExecException;
import net.sf.launch4j.FileChooserFilter;
import net.sf.launch4j.Log;
import net.sf.launch4j.Main;
import net.sf.launch4j.Util;
import net.sf.launch4j.binding.BindingException;
import net.sf.launch4j.binding.InvariantViolationException;
import net.sf.launch4j.config.Config;
import net.sf.launch4j.config.ConfigPersister;
import net.sf.launch4j.config.ConfigPersisterException;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class MainFrame extends JFrame {
	private static MainFrame _instance;

	private final JToolBar _toolBar;
	private final JButton _runButton;
	private final ConfigFormImpl _configForm;
	private final JFileChooser _fileChooser = new JFileChooser();
	private File _outfile;
	private boolean _saved = false;

	public static void createInstance() {
		try {
			Toolkit.getDefaultToolkit().setDynamicLayout(true);
			System.setProperty("sun.awt.noerasebackground","true");
	
			// JGoodies
			Options.setDefaultIconSize(new Dimension(16, 16));		// menu icons
			Options.setUseNarrowButtons(false);
			Options.setPopupDropShadowEnabled(true);

			UIManager.setLookAndFeel(new PlasticXPLookAndFeel());
			_instance = new MainFrame();
		} catch (Exception e) {
			System.err.println(e);
		}
	}

	public static MainFrame getInstance() {
		return _instance;
	}

	public MainFrame() {
		showConfigName(null);
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		addWindowListener(new ExitWindowListener());
		setGlassPane(new GlassPane(this));
		_fileChooser.setFileFilter(new FileChooserFilter("launch4j config files (.xml, .cfg)",
				new String[] {".xml", ".cfg"}));

		_toolBar = new JToolBar();
		_toolBar.setFloatable(false);
		_toolBar.setRollover(true);
		addButton("images/new.png", "New configuration", new NewActionListener());
		addButton("images/open.png", "Open configuration or import 1.x", new OpenActionListener());
		addButton("images/save.png", "Save configuration",  new SaveActionListener());
		_toolBar.addSeparator();
		addButton("images/build.png", "Build wrapper", new BuildActionListener());
		_runButton = addButton("images/run.png", "Test wrapper", new RunActionListener());
		setRunEnabled(false);
		_toolBar.addSeparator();
		addButton("images/info.png", "About launch4j", new AboutActionListener());

		_configForm = new ConfigFormImpl();
		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(_toolBar, BorderLayout.NORTH);
		getContentPane().add(_configForm, BorderLayout.CENTER);
		_configForm.clear();
		pack();
		Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
		Dimension fr = getSize();
		fr.height += 100;
		setBounds((scr.width - fr.width) / 2, (scr.height - fr.height) / 2, fr.width, fr.height);
		setVisible(true);
	}

	private JButton addButton(String iconPath, String tooltip, ActionListener l) {
		ImageIcon icon = new ImageIcon(MainFrame.class.getClassLoader().getResource(iconPath));
		JButton b = new JButton(icon);
		b.setToolTipText(tooltip);
		b.addActionListener(l);
		_toolBar.add(b);
		return b;
	}
	
	public void info(String text) {
		JOptionPane.showMessageDialog(this, 
									text,
									Main.PROGRAM_NAME,
									JOptionPane.INFORMATION_MESSAGE);
	}

	public void warn(String text) {
		JOptionPane.showMessageDialog(this, 
									text,
									Main.PROGRAM_NAME,
									JOptionPane.WARNING_MESSAGE);
	}

	public void warn(InvariantViolationException e) {
		e.getBinding().markInvalid();
		warn(e.getMessage());
		e.getBinding().markValid();
	}

	private boolean isModified() {
		return (!_configForm.isModified())
				|| JOptionPane.showConfirmDialog(MainFrame.this,
						"Discard changes?",
						"Confirm",
						JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
	}
	
	private boolean save() {
		// XXX
		try {
			_configForm.get(ConfigPersister.getInstance().getConfig());
			if (_fileChooser.showSaveDialog(MainFrame.this) == JOptionPane.YES_OPTION) {
				File f = _fileChooser.getSelectedFile();
				if (!f.getPath().endsWith(".xml")) {
					f = new File(f.getPath() + ".xml");
				}
				ConfigPersister.getInstance().save(f);
				_saved = true;
				showConfigName(f);
				return true;
			}
			return false;
		} catch (InvariantViolationException ex) {
			warn(ex);
			return false;
		} catch (BindingException ex) {
			warn(ex.getMessage());
			return false;
		} catch (ConfigPersisterException ex) {
			warn(ex.getMessage());
			return false;
		}
	}

	private void showConfigName(File config) {
		setTitle(Main.PROGRAM_NAME + " - " + (config != null ? config.getName() : "untitled"));
	}

	private void setRunEnabled(boolean enabled) {
		if (!enabled) {
			_outfile = null;
		}
		_runButton.setEnabled(enabled);
	}

	private class ExitWindowListener extends WindowAdapter {
		public void windowClosing(WindowEvent e) {
			if (isModified()) {
				System.exit(0);
			}
		}
	}

	private class NewActionListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			if (isModified()) {
				ConfigPersister.getInstance().createBlank();
				_configForm.put(ConfigPersister.getInstance().getConfig());
			}
			_saved = false;
			showConfigName(null);
			setRunEnabled(false);
		}
	}

	private class OpenActionListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			try {
				if (isModified()
						&& _fileChooser.showOpenDialog(MainFrame.this) == JOptionPane.YES_OPTION) {
					final File f = _fileChooser.getSelectedFile(); 
					if (f.getPath().endsWith(".xml")) {
						ConfigPersister.getInstance().load(f);	
						_saved = true;
					} else {
						ConfigPersister.getInstance().loadVersion1(f);
						_saved = false;
					}
					_configForm.put(ConfigPersister.getInstance().getConfig());
					showConfigName(f);
					setRunEnabled(false);
				}
			} catch (ConfigPersisterException ex) {
				warn(ex.getMessage());
			}
		}
	}
	
	private class SaveActionListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			save();
		}
	}
	
	private class BuildActionListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			final Log log = Log.getSwingLog(_configForm.getLogTextArea());
			try {
				if ((!_saved || _configForm.isModified())
						&& !save()) {
					return;
				}
				log.clear();
				ConfigPersister.getInstance().getConfig().checkInvariants();
				Builder b = new Builder(log);
				_outfile = b.build();
				setRunEnabled(ConfigPersister.getInstance().getConfig().getHeaderType()
						== Config.GUI_HEADER);	// TODO fix console app test 
			} catch (InvariantViolationException ex) {
				setRunEnabled(false);
				ex.setBinding(_configForm.getBinding(ex.getProperty()));	// XXX
				warn(ex);
			} catch (BuilderException ex) {
				setRunEnabled(false);
				log.append(ex.getMessage());
			}
		}
	}
	
	private class RunActionListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			try {
				getGlassPane().setVisible(true);
				Worker.post(new Task() {
					public Object run() throws ExecException {
						Log log = Log.getSwingLog(_configForm.getLogTextArea());
						log.clear();
						String path = _outfile.getPath();
						if (Util.WINDOWS_OS) {
							log.append("Executing: " + path);
							Util.exec(path, log);
						} else {
							log.append("Jar integrity test, executing: " + path);
							Util.exec("java -jar " + path, log);
						}
						return null;
					}
				});
			} catch (Exception ex) {
				// XXX errors logged by exec
			} finally {
				getGlassPane().setVisible(false);
			}
		};
	}

	private class AboutActionListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {
			info(Main.PROGRAM_DESCRIPTION
					+ "The following projects are used by launch4j...\n\n"
					+ "MinGW binutils (http://www.mingw.org/)\n"
					+ "Commons BeanUtils (http://jakarta.apache.org/commons/beanutils/)\n"
					+ "Commons Logging (http://jakarta.apache.org/commons/logging/)\n"
					+ "XStream (http://xstream.codehaus.org/)\n"
					+ "JGoodies Forms (http://www.jgoodies.com/freeware/forms/)\n"
					+ "JGoodies Looks (http://www.jgoodies.com/freeware/looks/)\n"
					+ "Foxtrot (http://foxtrot.sourceforge.net/)\n"
					+ "Nuvola Icon Theme (http://www.icon-king.com)");
		}
	}
}
