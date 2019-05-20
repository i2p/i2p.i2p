/*
 * IzPack - Copyright 2001-2008 Julien Ponge, All Rights Reserved.
 *
 * http://izpack.org/
 * http://izpack.codehaus.org/
 *
 * Copyright 2002 Jan Blok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.izforge.izpack.panels.xinfo;

import java.util.Properties;

import com.izforge.izpack.api.data.InstallData;
import com.izforge.izpack.api.resource.Resources;
import com.izforge.izpack.installer.console.AbstractConsolePanel;
import com.izforge.izpack.installer.console.ConsolePanel;
import com.izforge.izpack.installer.panel.PanelView;
import com.izforge.izpack.util.Console;

/**
 * Modified from HelloConsolePanel and XInfoPanel
 *
 * https://trac.i2p2.de/ticket/2492
 * https://izpack.atlassian.net/browse/IZPACK-1631
 */
public class XInfoConsolePanel extends AbstractConsolePanel
{
    private final Resources resources;

    /**
     * The info to display.
     */
    private String info;

    /**
     * Constructs an {@code XInfoConsolePanel}.
     *
     * @param panel the parent panel/view. May be {@code null}
     */
    public XInfoConsolePanel(Resources resources, PanelView<ConsolePanel> panel)
    {
        super(panel);
        this.resources = resources;
    }

    public boolean run(InstallData installData, Properties properties)
    {
        return true;
    }

    /**
     * Runs the panel using the specified console.
     *
     * @param installData the installation data
     * @param console     the console
     * @return <tt>true</tt> if the panel ran successfully, otherwise <tt>false</tt>
     */
    @Override
    public boolean run(InstallData installData, Console console)
    {
        display(installData, console);
        return promptEndPanel(installData, console);
    }

    /**
     * Loads the info text.
     */
    private void loadInfo()
    {
        info = resources.getString("XInfoPanel.info", null, "Error : could not load the info text !");
    }

    /**
     * Parses the text for special variables.
     */
    private void parseText(InstallData installData)
    {
        // Parses the info text
        info = installData.getVariables().replace(info);
    }

    /**
     * Displays the panel.
     *
     * @param installData the installation data
     * @param console     the console
     */
    protected void display(InstallData installData, Console console)
    {
        // Text handling
        loadInfo();
        parseText(installData);
        // UI handling
        console.println(info);
    }
}
