package edu.internet2.ndt;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;

/**
 * Class that displays status of tests being run. It also provides methods to
 * set status message, record intention to stop tests, and to fetch the status
 * of whether the test is to be stopped.
 * */

public class StatusPanel extends JPanel {
	/**
	 * Compiler generated constant that is not related to current classes'
	 * specific functionality
	 */
	private static final long serialVersionUID = 2609233901130079136L;

	private int _iTestsCompleted; // variable used to record the count of
									// "finished" tests
	private int _iTestsNum; // total test count
	private boolean _bStop = false;

	private JLabel _labelTestNum = new JLabel();
	private JButton _buttonStop;
	private JProgressBar _progressBarObj = new JProgressBar();

	/*
	 * Constructor
	 * 
	 * @param testsNum Total number of tests scheduled to be run
	 * 
	 * @param sParamaEnableMultiple String indicating whether multiple tests
	 * have been scheduled
	 */
	public StatusPanel(int iParamTestsNum, String sParamEnableMultiple) {
		this._iTestsCompleted = 1;
		this._iTestsNum = iParamTestsNum;

		setTestNoLabelText();

		// If multiple tests are enabled to be run, then add information about
		// the test number being run
		if (sParamEnableMultiple != null) {
			add(_labelTestNum);
		}

		_progressBarObj.setMinimum(0);
		_progressBarObj.setMaximum(_iTestsNum);
		_progressBarObj.setValue(0);
		_progressBarObj.setStringPainted(true);
		if (_iTestsNum == 0) {
			_progressBarObj.setString("");
			_progressBarObj.setIndeterminate(true);
		} else {
			_progressBarObj.setString(NDTConstants
					.getMessageString("initialization"));
		}
		add(_progressBarObj);
		_buttonStop = new JButton(NDTConstants.getMessageString("stop"));
		_buttonStop.addActionListener(new ActionListener() {

			public void actionPerformed(ActionEvent e) {
				_bStop = true;
				_buttonStop.setEnabled(false);
				StatusPanel.this.setText(NDTConstants
						.getMessageString("stopping"));
			}

		});

		// If multiple tests are enabled to be run, provide user option to
		// stop the one currently running
		if (sParamEnableMultiple != null) {
			add(_buttonStop);
		}
	}

	/**
	 * Set Test number being run
	 * */
	private void setTestNoLabelText() {
		_labelTestNum.setText(NDTConstants.getMessageString("test") + " "
				+ _iTestsCompleted + " " + NDTConstants.getMessageString("of")
				+ " " + _iTestsNum);

	}

	/**
	 * Get intention to stop tests
	 * 
	 * @return boolean indicating intention to stop or not
	 * */
	public boolean wantToStop() {
		return _bStop;
	}

	/**
	 * End the currently running test
	 * */
	public void endTest() {
		_progressBarObj.setValue(_iTestsCompleted);
		_iTestsCompleted++;
		setTestNoLabelText();
	}

	/**
	 * Sets a string explaining progress of tests
	 * 
	 * @param sParamText
	 *            String status of test-run
	 * */
	public void setText(String sParamText) {
		if (!_progressBarObj.isIndeterminate()) {
			_progressBarObj.setString(sParamText);
		}
	}
} // end class StatusPanel
