

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.StringTokenizer;

public class GUIInstall extends Install {

    static final GridBagConstraints gbcLeft = new GridBagConstraints();
    static final GridBagConstraints gbcRight = new GridBagConstraints();
    static final GridBagConstraints gbcBottom = new GridBagConstraints();
    static {
        gbcLeft.anchor = GridBagConstraints.EAST;
        gbcRight.fill = GridBagConstraints.HORIZONTAL;
        gbcRight.gridwidth = GridBagConstraints.REMAINDER;
        gbcRight.weightx = 1.0;
        gbcBottom.weighty = 1.0;
        gbcBottom.gridwidth = GridBagConstraints.REMAINDER;
        gbcBottom.fill = GridBagConstraints.BOTH;
        gbcBottom.insets = new Insets(4, 4, 4, 4);
    }

    public static void main(String[] args) {
        new GUIInstall().runInstall();
    }

    private InstallFrame frame;
    boolean installing = false;
    ArrayList categories = new ArrayList();
    InstallCategory currentCategory = null;

    public String handleSpliceParams(String s) {
        // any better ideas?
        return s;
    }

    public GUIInstall() {
        frame = new InstallFrame();
    }

    public void showStatus(String s) {
        if (!installing) throw new RuntimeException("Not installing yet!");
        frame.showStatus(s);
    }

    public void showOptError(String s) {
        frame.showOptError(s);
    }

    public void handleOptInfo(String s) {
        currentCategory.addInfo(s);
    }

    public void startOptCategory(String s) {
        currentCategory = new InstallCategory();
        categories.add(currentCategory);
    }

    public void finishOptions() {
        frame.startInstall(categories);
        System.out.println("Starting install...");
    }

    public void handleOption(int number, String question, String def, String type) {
        currentCategory.addOption(number, question, def, type);
    }

    public boolean confirmOption(String question, boolean defaultYes) {
        ConfirmFrame cf = new ConfirmFrame(frame, question, defaultYes);
        return cf.getResult();
    }

    private class ConfirmFrame extends Dialog {
        private boolean result;

        private ConfirmFrame(Frame parent, String msg, boolean defaultYes) {
            super(parent, "Installer question", true);
            setBackground(Color.lightGray);
            setLayout(new BorderLayout());
            TextArea ta;
            Panel p;
            Button b1, b2;
            add("Center", ta = new TextArea(msg, 3, 80));
            ta.setEditable(false);
            add("South", p = new Panel(new FlowLayout()));
            p.add(b1 = new Button("Yes"));
            p.add(b2 = new Button("No"));
            ActionListener al = new ActionListener() {
                public void actionPerformed(ActionEvent evt) {
                    result = evt.getActionCommand().equals("Yes");
                    ConfirmFrame.this.dispose();
                }
            };
            b1.addActionListener(al);
            b2.addActionListener(al);
            pack();

            // java 1.4
            //setLocationRelativeTo(parent);
            show();
            (defaultYes ? b1 : b2).requestFocus();
        }

        private boolean getResult() {
            return result;
        }
    }

    private class InstallCategory extends ArrayList {
        public void addInfo(String s) {
            add(new InfoOption(s));
        }

        public void addOption(int number, String question, String def, String type) {
            add(new RealOption(number, question, def, type));
        }
    }

    private interface InstallOption {
        public Component getComponent1();

        public Component getComponent2();

        public boolean setValue();

        public String getQuestion();
    }

    private class InfoOption extends Panel implements InstallOption {

        public InfoOption(String s) {
            super(new GridLayout(0, 1, 0, 0));
            for (StringTokenizer st = new StringTokenizer(s, "\n"); st.hasMoreTokens();) {
                add(new Label(st.nextToken()));
            }
        }

        public Component getComponent1() {
            return null;
        }

        public Component getComponent2() {
            return this;
        }

        public boolean setValue() {
            return true;
        }

        public String getQuestion() {
            return "<no question>";
        }
    }

    private class RealOption implements InstallOption {

        private int number;
        private String def, question;
        private Label l;
        private TextField t;

        public RealOption(int number, String question, String def, String type) {
            this.number = number;
            l = new Label(question);
            t = new TextField(def);
            this.def = def;
            this.question = question;
            // type is not needed yet
        }

        public void reset() {
            t.setText(def);
        }

        public String getQuestion() {
            return question;
        }

        public boolean setValue() {
            return GUIInstall.this.setOption(number, t.getText());
        }

        public Component getComponent1() {
            return l;
        }

        public Component getComponent2() {
            return t;
        }

    }

    private class InstallFrame extends Frame {

        private int current = -1;
        private Panel cats;
        private CardLayout cl;
        private boolean windowOpen = true;
        private TextArea log;

        public InstallFrame() {
            super("I2P Installer");
            setBackground(Color.lightGray);
            Panel p;
            Button b;
            setLayout(new BorderLayout());
            add("Center", cats = new Panel(cl = new CardLayout()));
            cats.add("Start", p = new Panel(new BorderLayout()));
            p.add("Center", new Label("Loading installer..."));
            cats.add("Install", p = new Panel(new BorderLayout()));
            p.add("Center", log = new TextArea("Installing...\n\n"));
            log.setEditable(false);
            add("South", p = new Panel(new FlowLayout()));
            p.add(b = new Button("<< Back"));
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent evt) {
                    if (current > 0) {
                        current--;
                        cl.show(cats, "" + current);
                    }
                }
            });
            p.add(b = new Button("Next >>"));
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent evt) {
                    if (current != -1) {
                        if (!saveCurrent()) return;
                        current++;
                        if (current == categoryPanels.length) {
                            cl.show(cats, "Install");
                            current = -1;
                            synchronized (InstallFrame.this) {
                                installing = true;
                                windowOpen = false;
                                InstallFrame.this.notify();
                            }
                        } else {
                            cl.show(cats, "" + current);
                        }
                    }
                }
            });
            p.add(b = new Button("Quit"));
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent evt) {
                    System.exit(0);
                }
            });
            addWindowListener(new WindowAdapter() {
                public void windowClosing(WindowEvent evt) {
                    System.exit(0);
                }
            });
            setSize(600, 450);

            // java 1.4
            //setLocationRelativeTo(null);
            show();
        }

        public void showStatus(String s) {
            log.append(s + "\n");
        }

        public void showOptError(String s) {
            if (current == -1) throw new RuntimeException("No options here!");
            categoryPanels[current].showError(s);
        }

        private CategoryPanel[] categoryPanels;

        public void startInstall(ArrayList categories) {
            Panel p;
            categoryPanels = new CategoryPanel[categories.size()];
            //build a panel for each category
            Iterator it = categories.iterator();
            for (int i = 0; it.hasNext(); i++) {
                cats.add("" + i, categoryPanels[i] = new CategoryPanel((InstallCategory) it.next()));
            }
            current = 0;
            cl.show(cats, "0");
            // wait till config is complete
            synchronized (this) {
                while (windowOpen) {
                    try {
                        wait();
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }

        private boolean saveCurrent() {
            return categoryPanels[current].saveOptions();
        }
    }

    private class CategoryPanel extends Panel {

        private TextArea errorBox;
        private InstallCategory ic;

        public CategoryPanel(InstallCategory ic) {
            super(new GridBagLayout());
            this.ic = ic;
            for (Iterator it = ic.iterator(); it.hasNext();) {
                InstallOption io = (InstallOption) it.next();
                Component c1 = io.getComponent1(), c2 = io.getComponent2();
                if (c1 != null) add(c1, gbcLeft);
                add(c2, gbcRight);
            }
            add(errorBox = new TextArea(), gbcBottom);
            errorBox.setEditable(false);
        }

        private InstallOption currentOption;

        public boolean saveOptions() {
            errorBox.setText("Saving options...\n\n");
            for (Iterator it = ic.iterator(); it.hasNext();) {
                InstallOption io = (InstallOption) it.next();
                currentOption = io;
                if (!io.setValue()) return false;
                currentOption = null;
            }
            return true;
        }

        public void showError(String s) {
            if (currentOption == null) { throw new RuntimeException("No option to test"); }
            errorBox.append("While setting \"" + currentOption.getQuestion() + "\":\n" + s + "\n\n");
        }
    }
}