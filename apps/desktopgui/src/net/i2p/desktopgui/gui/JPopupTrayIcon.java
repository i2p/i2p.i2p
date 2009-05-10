/*
* Created on Sep 15, 2008  5:51:33 PM
*/

/*
 * This class is part of fishfarm project: https://fishfarm.dev.java.net/
 * It is licensed under the GPL version 2.0 with Classpath Exception.
 * 
 * Copyright (C) 2008  Michael Bien
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 * 
 */

package net.i2p.desktopgui.gui;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JDialog;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.RootPaneContainer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.util.Date;



/**
 * JPopupMenu compatible TrayIcon based on Alexander Potochkin's JXTrayIcon
 * (http://weblogs.java.net/blog/alexfromsun/archive/2008/02/jtrayicon_updat.html)
 * but uses a JWindow instead of a JDialog to workaround some bugs on linux.
 *
 * @author Michael Bien
 */
public class JPopupTrayIcon extends TrayIcon {

    private JPopupMenu menu;
    
    private Window window;
    private PopupMenuListener popupListener;
    
    private final static boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("windows");

    private static MouseEvent previous = null;
    private static Date previousTime = new Date();
    private static Date time = new Date();

    public JPopupTrayIcon(Image image) {
        super(image);
        init();
    }

    public JPopupTrayIcon(Image image, String tooltip) {
        super(image, tooltip);
        init();
    }

    public JPopupTrayIcon(Image image, String tooltip, PopupMenu popup) {
        super(image, tooltip, popup);
        init();
    }

    public JPopupTrayIcon(Image image, String tooltip, JPopupMenu popup) {
        super(image, tooltip);
        init();
        setJPopupMenu(popup);
    }


    private final void init() {


        popupListener = new PopupMenuListener() {

            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                //System.out.println("popupMenuWillBecomeVisible");
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                //System.out.println("popupMenuWillBecomeInvisible");
                if(window != null) {
                    window.dispose();
                    window = null;
                }
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
//                System.out.println("popupMenuCanceled");
                if(window != null) {
                    window.dispose();
                    window = null;
                }
            }
        };

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                //System.out.println("Pressed " + e.getPoint());
                showJPopupMenu(e, previous);
                previous = e;
                previousTime = time;
                time = new Date();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                //System.out.println("Released " + e.getPoint());
                showJPopupMenu(e, previous);
                previous = e;
                previousTime = time;
                time = new Date();
            }
        });

    }

    private final void showJPopupMenu(MouseEvent e, MouseEvent previous) {
        if((e.isPopupTrigger() || previous.isPopupTrigger()) && (time.getTime() - previousTime.getTime() < 1000) && menu != null) {
            if (window == null) {

                if(IS_WINDOWS) {
                    window = new JDialog((Frame)null);
                    ((JDialog)window).setUndecorated(true);
                }else{
                    window = new JWindow((Frame)null);
                }
                window.setAlwaysOnTop(true);
                Dimension size = menu.getPreferredSize();

                Point centerPoint = GraphicsEnvironment.getLocalGraphicsEnvironment().getCenterPoint();
                if(e.getY() > centerPoint.getY())
                    window.setLocation(e.getX(), e.getY() - size.height);
                else
                    window.setLocation(e.getX(), e.getY());

                window.setVisible(true);
                
                menu.show(((RootPaneContainer)window).getContentPane(), 0, 0);

                // popup works only for focused windows
                window.toFront();

            }
        }
    }


    public final JPopupMenu getJPopupMenu() {
        return menu;
    }

    public final void setJPopupMenu(JPopupMenu menu) {
        if (this.menu != null) {
            this.menu.removePopupMenuListener(popupListener);
        }
        this.menu = menu;
        menu.addPopupMenuListener(popupListener);
    }

}
