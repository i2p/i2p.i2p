package net.sf.launch4j.form;

import com.jeta.forms.components.separator.TitledSeparator;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;


public abstract class ConfigForm extends JPanel
{
   protected final JTabbedPane _tab = new JTabbedPane();
   protected final JButton _outfileButton = new JButton();
   protected final JTextField _outfileField = new JTextField();
   protected final JTextField _errorTitleField = new JTextField();
   protected final JCheckBox _customProcNameCheck = new JCheckBox();
   protected final JCheckBox _stayAliveCheck = new JCheckBox();
   protected final JTextField _iconField = new JTextField();
   protected final JTextField _jarField = new JTextField();
   protected final JButton _jarButton = new JButton();
   protected final JButton _iconButton = new JButton();
   protected final JTextField _jarArgsField = new JTextField();
   protected final JTextField _chdirField = new JTextField();
   protected final JRadioButton _guiHeaderRadio = new JRadioButton();
   protected final ButtonGroup _headerButtonGroup = new ButtonGroup();
   protected final JRadioButton _consoleHeaderRadio = new JRadioButton();
   protected final JList _objectFileList = new JList();
   protected final JList _w32apiList = new JList();
   protected final JTextField _jrePathField = new JTextField();
   protected final JTextField _jreMinField = new JTextField();
   protected final JTextField _jreMaxField = new JTextField();
   protected final JTextArea _jvmArgsTextArea = new JTextArea();
   protected final JTextField _initialHeapSizeField = new JTextField();
   protected final JTextField _maxHeapSizeField = new JTextField();
   protected final JCheckBox _timeoutErrCheck = new JCheckBox();
   protected final JTextField _splashFileField = new JTextField();
   protected final JTextField _timeoutField = new JTextField();
   protected final JButton _splashFileButton = new JButton();
   protected final JCheckBox _splashCheck = new JCheckBox();
   protected final JCheckBox _waitForWindowCheck = new JCheckBox();
   protected final JCheckBox _versionInfoCheck = new JCheckBox();
   protected final JTextField _fileVersionField = new JTextField();
   protected final JTextField _productVersionField = new JTextField();
   protected final JTextField _fileDescriptionField = new JTextField();
   protected final JTextField _copyrightField = new JTextField();
   protected final JTextField _txtFileVersionField = new JTextField();
   protected final JTextField _txtProductVersionField = new JTextField();
   protected final JTextField _productNameField = new JTextField();
   protected final JTextField _originalFilenameField = new JTextField();
   protected final JTextField _internalNameField = new JTextField();
   protected final JTextField _companyNameField = new JTextField();
   protected final JTextArea _logTextArea = new JTextArea();

   /**
    * Default constructor
    */
   public ConfigForm()
   {
      initializePanel();
   }

   /**
    * Adds fill components to empty cells in the first row and first column of the grid.
    * This ensures that the grid spacing will be the same as shown in the designer.
    * @param cols an array of column indices in the first row where fill components should be added.
    * @param rows an array of row indices in the first column where fill components should be added.
    */
   void addFillComponents( Container panel, int[] cols, int[] rows )
   {
      Dimension filler = new Dimension(10,10);

      boolean filled_cell_11 = false;
      CellConstraints cc = new CellConstraints();
      if ( cols.length > 0 && rows.length > 0 )
      {
         if ( cols[0] == 1 && rows[0] == 1 )
         {
            /** add a rigid area  */
            panel.add( Box.createRigidArea( filler ), cc.xy(1,1) );
            filled_cell_11 = true;
         }
      }

      for( int index = 0; index < cols.length; index++ )
      {
         if ( cols[index] == 1 && filled_cell_11 )
         {
            continue;
         }
         panel.add( Box.createRigidArea( filler ), cc.xy(cols[index],1) );
      }

      for( int index = 0; index < rows.length; index++ )
      {
         if ( rows[index] == 1 && filled_cell_11 )
         {
            continue;
         }
         panel.add( Box.createRigidArea( filler ), cc.xy(1,rows[index]) );
      }

   }

   /**
    * Helper method to load an image file from the CLASSPATH
    * @param imageName the package and name of the file to load relative to the CLASSPATH
    * @return an ImageIcon instance with the specified image file
    * @throws IllegalArgumentException if the image resource cannot be loaded.
    */
   public ImageIcon loadImage( String imageName )
   {
      try
      {
         ClassLoader classloader = getClass().getClassLoader();
         java.net.URL url = classloader.getResource( imageName );
         if ( url != null )
         {
            ImageIcon icon = new ImageIcon( url );
            return icon;
         }
      }
      catch( Exception e )
      {
         e.printStackTrace();
      }
      throw new IllegalArgumentException( "Unable to load image: " + imageName );
   }

   public JPanel createPanel()
   {
      JPanel jpanel1 = new JPanel();
      FormLayout formlayout1 = new FormLayout("FILL:3DLU:NONE,FILL:DEFAULT:GROW(1.0),FILL:3DLU:NONE","CENTER:3DLU:NONE,FILL:DEFAULT:NONE,CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,FILL:DEFAULT:GROW(1.0),CENTER:3DLU:NONE");
      CellConstraints cc = new CellConstraints();
      jpanel1.setLayout(formlayout1);

      _tab.setName("tab");
      _tab.addTab("Basic",null,createPanel1());
      _tab.addTab("Header",null,createPanel2());
      _tab.addTab("JRE",null,createPanel3());
      _tab.addTab("Splash",null,createPanel4());
      _tab.addTab("Version Info",null,createPanel5());
      jpanel1.add(_tab,cc.xy(2,2));

      _logTextArea.setName("logTextArea");
      JScrollPane jscrollpane1 = new JScrollPane();
      jscrollpane1.setViewportView(_logTextArea);
      jscrollpane1.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
      jscrollpane1.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      jpanel1.add(jscrollpane1,cc.xy(2,6));

      TitledSeparator titledseparator1 = new TitledSeparator();
      titledseparator1.setText("Log");
      jpanel1.add(titledseparator1,cc.xy(2,4));

      addFillComponents(jpanel1,new int[]{ 1,2,3 },new int[]{ 1,2,3,4,5,6,7 });
      return jpanel1;
   }

   public JPanel createPanel1()
   {
      JPanel jpanel1 = new JPanel();
      FormLayout formlayout1 = new FormLayout("FILL:7DLU:NONE,RIGHT:MAX(55DLU;DEFAULT):NONE,FILL:3DLU:NONE,FILL:DEFAULT:GROW(1.0),FILL:3DLU:NONE,FILL:26PX:NONE,FILL:7DLU:NONE","CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:9DLU:NONE");
      CellConstraints cc = new CellConstraints();
      jpanel1.setLayout(formlayout1);

      _outfileButton.setIcon(loadImage("images/open16.png"));
      _outfileButton.setName("outfileButton");
      jpanel1.add(_outfileButton,cc.xy(6,2));

      JLabel jlabel1 = new JLabel();
      jlabel1.setText("<html><b>Output file</b></html>");
      jpanel1.add(jlabel1,cc.xy(2,2));

      JLabel jlabel2 = new JLabel();
      jlabel2.setText("Error title");
      jpanel1.add(jlabel2,cc.xy(2,12));

      _outfileField.setName("outfileField");
      _outfileField.setToolTipText("Output executable file.");
      jpanel1.add(_outfileField,cc.xy(4,2));

      _errorTitleField.setName("errorTitleField");
      _errorTitleField.setToolTipText("Launch4j signals errors using a message box, you can set it's title to the application's name.");
      jpanel1.add(_errorTitleField,cc.xy(4,12));

      _customProcNameCheck.setActionCommand("Custom process name");
      _customProcNameCheck.setName("customProcNameCheck");
      _customProcNameCheck.setText("Custom process name");
      jpanel1.add(_customProcNameCheck,cc.xy(4,14));

      _stayAliveCheck.setActionCommand("Stay alive after launching a GUI application");
      _stayAliveCheck.setName("stayAliveCheck");
      _stayAliveCheck.setText("Stay alive after launching a GUI application");
      jpanel1.add(_stayAliveCheck,cc.xy(4,16));

      JLabel jlabel3 = new JLabel();
      jlabel3.setText("Icon");
      jpanel1.add(jlabel3,cc.xy(2,6));

      _iconField.setName("iconField");
      _iconField.setToolTipText("Application icon.");
      jpanel1.add(_iconField,cc.xy(4,6));

      _jarField.setName("jarField");
      _jarField.setToolTipText("Application jar.");
      jpanel1.add(_jarField,cc.xy(4,4));

      JLabel jlabel4 = new JLabel();
      jlabel4.setText("<html><b>Jar</b></html>");
      jpanel1.add(jlabel4,cc.xy(2,4));

      _jarButton.setIcon(loadImage("images/open16.png"));
      _jarButton.setName("jarButton");
      jpanel1.add(_jarButton,cc.xy(6,4));

      _iconButton.setIcon(loadImage("images/open16.png"));
      _iconButton.setName("iconButton");
      jpanel1.add(_iconButton,cc.xy(6,6));

      JLabel jlabel5 = new JLabel();
      jlabel5.setText("Jar arguments");
      jpanel1.add(jlabel5,cc.xy(2,10));

      _jarArgsField.setName("jarArgsField");
      _jarArgsField.setToolTipText("Constant command line arguments passed to the application.");
      jpanel1.add(_jarArgsField,cc.xy(4,10));

      JLabel jlabel6 = new JLabel();
      jlabel6.setText("Options");
      jpanel1.add(jlabel6,cc.xy(2,14));

      JLabel jlabel7 = new JLabel();
      jlabel7.setText("Change dir");
      jpanel1.add(jlabel7,cc.xy(2,8));

      _chdirField.setName("chdirField");
      _chdirField.setToolTipText("Change current directory to a location relative to the executable. Empty field has no effect, . - changes directory to the exe location.");
      jpanel1.add(_chdirField,cc.xy(4,8));

      addFillComponents(jpanel1,new int[]{ 1,2,3,4,5,6,7 },new int[]{ 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17 });
      return jpanel1;
   }

   public JPanel createPanel2()
   {
      JPanel jpanel1 = new JPanel();
      FormLayout formlayout1 = new FormLayout("FILL:7DLU:NONE,RIGHT:MAX(55DLU;DEFAULT):NONE,FILL:3DLU:NONE,FILL:DEFAULT:NONE,FILL:7DLU:NONE,FILL:DEFAULT:NONE,FILL:DEFAULT:GROW(1.0),FILL:7DLU:NONE","CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,FILL:DEFAULT:GROW(0.2),CENTER:3DLU:NONE,FILL:DEFAULT:GROW(1.0),CENTER:9DLU:NONE");
      CellConstraints cc = new CellConstraints();
      jpanel1.setLayout(formlayout1);

      JLabel jlabel1 = new JLabel();
      jlabel1.setText("Header type");
      jpanel1.add(jlabel1,cc.xy(2,2));

      _guiHeaderRadio.setActionCommand("GUI");
      _guiHeaderRadio.setName("guiHeaderRadio");
      _guiHeaderRadio.setText("GUI");
      _headerButtonGroup.add(_guiHeaderRadio);
      jpanel1.add(_guiHeaderRadio,cc.xy(4,2));

      _consoleHeaderRadio.setActionCommand("Console");
      _consoleHeaderRadio.setName("consoleHeaderRadio");
      _consoleHeaderRadio.setText("Console");
      _headerButtonGroup.add(_consoleHeaderRadio);
      jpanel1.add(_consoleHeaderRadio,cc.xy(6,2));

      JLabel jlabel2 = new JLabel();
      jlabel2.setText("Object files");
      jpanel1.add(jlabel2,new CellConstraints(2,4,1,1,CellConstraints.DEFAULT,CellConstraints.TOP));

      _objectFileList.setName("objectFileList");
      _objectFileList.setToolTipText("Compiled header files.");
      JScrollPane jscrollpane1 = new JScrollPane();
      jscrollpane1.setViewportView(_objectFileList);
      jscrollpane1.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
      jscrollpane1.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      jpanel1.add(jscrollpane1,cc.xywh(4,4,4,1));

      JLabel jlabel3 = new JLabel();
      jlabel3.setText("w32api");
      jpanel1.add(jlabel3,new CellConstraints(2,6,1,1,CellConstraints.DEFAULT,CellConstraints.TOP));

      _w32apiList.setName("w32apiList");
      _w32apiList.setToolTipText("DLLs required by header.");
      JScrollPane jscrollpane2 = new JScrollPane();
      jscrollpane2.setViewportView(_w32apiList);
      jscrollpane2.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
      jscrollpane2.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      jpanel1.add(jscrollpane2,cc.xywh(4,6,4,1));

      addFillComponents(jpanel1,new int[]{ 1,2,3,4,5,6,7,8 },new int[]{ 1,2,3,4,5,6,7 });
      return jpanel1;
   }

   public JPanel createPanel3()
   {
      JPanel jpanel1 = new JPanel();
      FormLayout formlayout1 = new FormLayout("FILL:7DLU:NONE,RIGHT:MAX(55DLU;DEFAULT):NONE,FILL:3DLU:NONE,FILL:60DLU:NONE,FILL:3DLU:NONE,FILL:DEFAULT:NONE,FILL:DEFAULT:GROW(1.0),FILL:7DLU:NONE","CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,FILL:DEFAULT:GROW(1.0),CENTER:9DLU:NONE");
      CellConstraints cc = new CellConstraints();
      jpanel1.setLayout(formlayout1);

      JLabel jlabel1 = new JLabel();
      jlabel1.setText("<html><b>Emb. JRE path</b></html>");
      jpanel1.add(jlabel1,cc.xy(2,2));

      JLabel jlabel2 = new JLabel();
      jlabel2.setText("<html><b>Min JRE version</b></html>");
      jpanel1.add(jlabel2,cc.xy(2,4));

      JLabel jlabel3 = new JLabel();
      jlabel3.setText("Max JRE version");
      jpanel1.add(jlabel3,cc.xy(2,6));

      JLabel jlabel4 = new JLabel();
      jlabel4.setText("JVM arguments");
      jpanel1.add(jlabel4,new CellConstraints(2,12,1,1,CellConstraints.DEFAULT,CellConstraints.TOP));

      _jrePathField.setName("jrePathField");
      _jrePathField.setToolTipText("Embedded JRE path relative to the executable.");
      jpanel1.add(_jrePathField,cc.xywh(4,2,4,1));

      _jreMinField.setName("jreMinField");
      jpanel1.add(_jreMinField,cc.xy(4,4));

      _jreMaxField.setName("jreMaxField");
      jpanel1.add(_jreMaxField,cc.xy(4,6));

      _jvmArgsTextArea.setName("jvmArgsTextArea");
      _jvmArgsTextArea.setToolTipText("Accepts everything you would normally pass to java/javaw launcher: assertion options, system properties and X options.");
      JScrollPane jscrollpane1 = new JScrollPane();
      jscrollpane1.setViewportView(_jvmArgsTextArea);
      jscrollpane1.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
      jscrollpane1.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
      jpanel1.add(jscrollpane1,cc.xywh(4,12,4,1));

      JLabel jlabel5 = new JLabel();
      jlabel5.setText("Initial heap size");
      jpanel1.add(jlabel5,cc.xy(2,8));

      JLabel jlabel6 = new JLabel();
      jlabel6.setText("Max heap size");
      jpanel1.add(jlabel6,cc.xy(2,10));

      JLabel jlabel7 = new JLabel();
      jlabel7.setText("MB");
      jpanel1.add(jlabel7,cc.xy(6,8));

      JLabel jlabel8 = new JLabel();
      jlabel8.setText("MB");
      jpanel1.add(jlabel8,cc.xy(6,10));

      _initialHeapSizeField.setName("initialHeapSizeField");
      jpanel1.add(_initialHeapSizeField,cc.xy(4,8));

      _maxHeapSizeField.setName("maxHeapSizeField");
      jpanel1.add(_maxHeapSizeField,cc.xy(4,10));

      addFillComponents(jpanel1,new int[]{ 1,2,3,4,5,6,7,8 },new int[]{ 1,2,3,4,5,6,7,8,9,10,11,12,13 });
      return jpanel1;
   }

   public JPanel createPanel4()
   {
      JPanel jpanel1 = new JPanel();
      FormLayout formlayout1 = new FormLayout("FILL:7DLU:NONE,RIGHT:MAX(55DLU;DEFAULT):NONE,FILL:3DLU:NONE,FILL:60DLU:NONE,FILL:DEFAULT:GROW(1.0),FILL:3DLU:NONE,FILL:26PX:NONE,FILL:7DLU:NONE","CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:9DLU:NONE");
      CellConstraints cc = new CellConstraints();
      jpanel1.setLayout(formlayout1);

      JLabel jlabel1 = new JLabel();
      jlabel1.setText("Splash file");
      jpanel1.add(jlabel1,cc.xy(2,4));

      JLabel jlabel2 = new JLabel();
      jlabel2.setText("Wait for window");
      jpanel1.add(jlabel2,cc.xy(2,6));

      JLabel jlabel3 = new JLabel();
      jlabel3.setText("Timeout [s]");
      jpanel1.add(jlabel3,cc.xy(2,8));

      _timeoutErrCheck.setActionCommand("Signal error on timeout");
      _timeoutErrCheck.setName("timeoutErrCheck");
      _timeoutErrCheck.setText("Signal error on timeout");
      _timeoutErrCheck.setToolTipText("True signals an error on splash timeout, false closes the splash screen quietly.");
      jpanel1.add(_timeoutErrCheck,cc.xywh(4,10,2,1));

      _splashFileField.setName("splashFileField");
      _splashFileField.setToolTipText("Splash screen file in BMP format.");
      jpanel1.add(_splashFileField,cc.xywh(4,4,2,1));

      _timeoutField.setName("timeoutField");
      _timeoutField.setToolTipText("Number of seconds after which the splash screen must close. Splash timeout may cause an error depending on splashTimeoutErr property.");
      jpanel1.add(_timeoutField,cc.xy(4,8));

      _splashFileButton.setIcon(loadImage("images/open16.png"));
      _splashFileButton.setName("splashFileButton");
      jpanel1.add(_splashFileButton,cc.xy(7,4));

      _splashCheck.setActionCommand("Enable splash screen");
      _splashCheck.setName("splashCheck");
      _splashCheck.setText("Enable splash screen");
      jpanel1.add(_splashCheck,cc.xywh(4,2,2,1));

      _waitForWindowCheck.setActionCommand("Close splash screen when an application window appears");
      _waitForWindowCheck.setName("waitForWindowCheck");
      _waitForWindowCheck.setText("Close splash screen when an application window appears");
      jpanel1.add(_waitForWindowCheck,cc.xywh(4,6,2,1));

      addFillComponents(jpanel1,new int[]{ 1,2,3,4,5,6,7,8 },new int[]{ 1,2,3,4,5,6,7,8,9,10,11 });
      return jpanel1;
   }

   public JPanel createPanel5()
   {
      JPanel jpanel1 = new JPanel();
      FormLayout formlayout1 = new FormLayout("FILL:7DLU:NONE,RIGHT:MAX(55DLU;DEFAULT):NONE,FILL:3DLU:NONE,FILL:60DLU:NONE,FILL:7DLU:NONE,RIGHT:DEFAULT:NONE,FILL:3DLU:NONE,FILL:DEFAULT:GROW(1.0),FILL:7DLU:NONE","CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:9DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:3DLU:NONE,CENTER:DEFAULT:NONE,CENTER:9DLU:NONE");
      CellConstraints cc = new CellConstraints();
      jpanel1.setLayout(formlayout1);

      _versionInfoCheck.setActionCommand("Add version information");
      _versionInfoCheck.setName("versionInfoCheck");
      _versionInfoCheck.setText("Add version information");
      jpanel1.add(_versionInfoCheck,cc.xywh(4,2,5,1));

      JLabel jlabel1 = new JLabel();
      jlabel1.setText("File version");
      jpanel1.add(jlabel1,cc.xy(2,4));

      _fileVersionField.setName("fileVersionField");
      _fileVersionField.setToolTipText("Version number 'x.x.x.x'");
      jpanel1.add(_fileVersionField,cc.xy(4,4));

      TitledSeparator titledseparator1 = new TitledSeparator();
      titledseparator1.setText("Additional information");
      jpanel1.add(titledseparator1,cc.xywh(2,10,7,1));

      JLabel jlabel2 = new JLabel();
      jlabel2.setText("Product version");
      jpanel1.add(jlabel2,cc.xy(2,12));

      _productVersionField.setName("productVersionField");
      _productVersionField.setToolTipText("Version number 'x.x.x.x'");
      jpanel1.add(_productVersionField,cc.xy(4,12));

      JLabel jlabel3 = new JLabel();
      jlabel3.setText("File description");
      jpanel1.add(jlabel3,cc.xy(2,6));

      _fileDescriptionField.setName("fileDescriptionField");
      _fileDescriptionField.setToolTipText("File description presented to the user.");
      jpanel1.add(_fileDescriptionField,cc.xywh(4,6,5,1));

      JLabel jlabel4 = new JLabel();
      jlabel4.setText("Copyright");
      jpanel1.add(jlabel4,cc.xy(2,8));

      _copyrightField.setName("copyrightField");
      jpanel1.add(_copyrightField,cc.xywh(4,8,5,1));

      JLabel jlabel5 = new JLabel();
      jlabel5.setText("Free form");
      jpanel1.add(jlabel5,cc.xy(6,4));

      _txtFileVersionField.setName("txtFileVersionField");
      _txtFileVersionField.setToolTipText("Free form file version, for example '1.20.RC1'.");
      jpanel1.add(_txtFileVersionField,cc.xy(8,4));

      JLabel jlabel6 = new JLabel();
      jlabel6.setText("Free form");
      jpanel1.add(jlabel6,cc.xy(6,12));

      _txtProductVersionField.setName("txtProductVersionField");
      _txtProductVersionField.setToolTipText("Free form product version, for example '1.20.RC1'.");
      jpanel1.add(_txtProductVersionField,cc.xy(8,12));

      JLabel jlabel7 = new JLabel();
      jlabel7.setText("Product name");
      jpanel1.add(jlabel7,cc.xy(2,14));

      _productNameField.setName("productNameField");
      jpanel1.add(_productNameField,cc.xywh(4,14,5,1));

      JLabel jlabel8 = new JLabel();
      jlabel8.setText("Original filename");
      jpanel1.add(jlabel8,cc.xy(2,20));

      _originalFilenameField.setName("originalFilenameField");
      _originalFilenameField.setToolTipText("Original name of the file without the path. Allows to determine whether a file has been renamed by a user.");
      jpanel1.add(_originalFilenameField,cc.xywh(4,20,5,1));

      JLabel jlabel9 = new JLabel();
      jlabel9.setText("Internal name");
      jpanel1.add(jlabel9,cc.xy(2,18));

      _internalNameField.setName("internalNameField");
      _internalNameField.setToolTipText("Internal name without extension, original filename or module name for example.");
      jpanel1.add(_internalNameField,cc.xywh(4,18,5,1));

      JLabel jlabel10 = new JLabel();
      jlabel10.setText("Company name");
      jpanel1.add(jlabel10,cc.xy(2,16));

      _companyNameField.setName("companyNameField");
      jpanel1.add(_companyNameField,cc.xywh(4,16,5,1));

      addFillComponents(jpanel1,new int[]{ 1,2,3,4,5,6,7,8,9 },new int[]{ 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21 });
      return jpanel1;
   }

   /**
    * Initializer
    */
   protected void initializePanel()
   {
      setLayout(new BorderLayout());
      add(createPanel(), BorderLayout.CENTER);
   }


}
