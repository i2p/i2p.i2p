package net.i2p.desktopgui;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.SwingWorker;

import net.i2p.desktopgui.router.RouterManager;
import net.i2p.util.Log;

public class ExternalTrayManager extends TrayManager {
	
	private final static Log log = new Log(ExternalTrayManager.class);

	protected ExternalTrayManager() {}

	@Override
	public PopupMenu getMainMenu() {
		PopupMenu popup = new PopupMenu();
        MenuItem startItem = new MenuItem(_("Start I2P"));
        startItem.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent arg0) {
				new SwingWorker<Object, Object>() {

					@Override
					protected Object doInBackground() throws Exception {
						RouterManager.start();
						return null;
					}
					
					@Override
					protected void done() {
						trayIcon.displayMessage(_("Starting"), _("I2P is starting!"), TrayIcon.MessageType.INFO);
						//Hide the tray icon.
						//We cannot stop the desktopgui program entirely,
						//since that risks killing the I2P process as well.
						tray.remove(trayIcon);
					}
					
				}.execute();
			}
        	
        });
        popup.add(startItem);
		return popup;
	}
}
