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
 * Created on May 10, 2005
 */
package net.sf.launch4j.formimpl;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.JFileChooser;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import net.sf.launch4j.FileChooserFilter;
import net.sf.launch4j.binding.Binding;
import net.sf.launch4j.binding.Bindings;
import net.sf.launch4j.binding.IValidatable;
import net.sf.launch4j.config.Splash;
import net.sf.launch4j.config.VersionInfo;
import net.sf.launch4j.form.ConfigForm;

/**
 * @author Copyright (C) 2005 Grzegorz Kowal
 */
public class ConfigFormImpl extends ConfigForm {
	private final JFileChooser _fileChooser = new JFileChooser();
	private final Bindings _bindings = new Bindings();

	public ConfigFormImpl() {
		_outfileButton.addActionListener(new BrowseActionListener(
				_outfileField, new FileChooserFilter("Windows executables (.exe)", ".exe")));
		_jarButton.addActionListener(new BrowseActionListener(
				_jarField, new FileChooserFilter("Jar files", ".jar")));
		_iconButton.addActionListener(new BrowseActionListener(
				_iconField, new FileChooserFilter("Icon files (.ico)", ".ico")));
		_splashFileButton.addActionListener(new BrowseActionListener(
				_splashFileField, new FileChooserFilter("Bitmap files (.bmp)", ".bmp")));

		_bindings.add("outfile", _outfileField)
				.add("jar", _jarField)
				.add("icon", _iconField)
				.add("jarArgs", _jarArgsField)
				.add("errTitle", _errorTitleField)
				.add("chdir", _chdirField)
				.add("customProcName", _customProcNameCheck)
				.add("stayAlive", _stayAliveCheck)
				.add("headerType", new JRadioButton[] {_guiHeaderRadio, _consoleHeaderRadio})
				// TODO header object files
				// TODO w32api
				.add("jre.path", _jrePathField)
				.add("jre.minVersion", _jreMinField)
				.add("jre.maxVersion", _jreMaxField)
				.add("jre.initialHeapSize", _initialHeapSizeField)
				.add("jre.maxHeapSize", _maxHeapSizeField)
				.add("jre.args", _jvmArgsTextArea)
				.addOptComponent("splash", Splash.class, _splashCheck)
				.add("splash.file", _splashFileField)
				.add("splash.waitForWindow", _waitForWindowCheck, true)
				.add("splash.timeout", _timeoutField, "60")
				.add("splash.timeoutErr", _timeoutErrCheck, true)
				.addOptComponent("versionInfo", VersionInfo.class, _versionInfoCheck)
				.add("versionInfo.fileVersion", _fileVersionField)
				.add("versionInfo.productVersion", _productVersionField)
				.add("versionInfo.fileDescription", _fileDescriptionField)
				.add("versionInfo.internalName", _internalNameField)
				.add("versionInfo.originalFilename", _originalFilenameField)
				.add("versionInfo.productName", _productNameField)
				.add("versionInfo.txtFileVersion", _txtFileVersionField)
				.add("versionInfo.txtProductVersion", _txtProductVersionField)
				.add("versionInfo.companyName", _companyNameField)
				.add("versionInfo.copyright", _copyrightField);
	}

	private class BrowseActionListener implements ActionListener {
		private final JTextField _field;
		private final FileChooserFilter _filter;

		public BrowseActionListener(JTextField field, FileChooserFilter filter) {
			_field = field;
			_filter = filter;
		}

		public void actionPerformed(ActionEvent e) {
			if (!_field.isEnabled()) {
				return;
			}
			_fileChooser.setFileFilter(_filter);
			_fileChooser.setSelectedFile(new File(""));
			int result = _field.equals(_outfileField)
					? _fileChooser.showSaveDialog(MainFrame.getInstance())
					: _fileChooser.showOpenDialog(MainFrame.getInstance());
			if (result == JFileChooser.APPROVE_OPTION) {
				_field.setText(_fileChooser.getSelectedFile().getPath());
			}
		}
	}
	
	public void clear() {
		_bindings.clear();
	}

	public void put(IValidatable bean) {
		_bindings.put(bean);
	}

	public void get(IValidatable bean) {
		_bindings.get(bean);
	}
	
	public boolean isModified() {
		return _bindings.isModified();
	}
	
	public JTextArea getLogTextArea() {
		return _logTextArea;
	}
	
	public Binding getBinding(String property) {
		return _bindings.getBinding(property);
	}
}
