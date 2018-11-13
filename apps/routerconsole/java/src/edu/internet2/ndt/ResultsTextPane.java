package edu.internet2.ndt;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import java.awt.*;

/*
 * Class that extends TextPane. This Text-pane is used as the chief
 * Results window that summarizes the results of all tests 
 * that have been run.
 * 
 * This class is declared separately so that it can be easily extended
 * by users to customize based on individual needs
 * 
 */
public class ResultsTextPane extends JTextPane {

	/**
	 * Compiler auto-generate value not directly related to class functionality
	 */
	private static final long serialVersionUID = -2224271202004876654L;

	/**
	 * Method to append String into the current document
	 * 
	 * @param paramTextStr
	 *            String to be inserted into the document
	 **/
	public void append(String paramTextStr) {
		try {
			getStyledDocument().insertString(getStyledDocument().getLength(),
					paramTextStr, null);
		} catch (BadLocationException e) {
			System.out
					.println("WARNING: failed to append text to the text pane! ["
							+ paramTextStr + "]");
		}
	}

	/**
	 * JTextPane method to insert a component into the document as a replacement
	 * for currently selected content. If no selection is made, the the
	 * component is inserted at the current position of the caret.
	 * 
	 * @param paramCompObj
	 *            the component to insert
	 * */
	public void insertComponent(Component paramCompObj) {
		setSelectionStart(this.getStyledDocument().getLength());
		setSelectionEnd(this.getStyledDocument().getLength());
		super.insertComponent(paramCompObj);
	}

}
