package net.i2p.i2ptunnel.access;

import java.io.File;
import java.io.IOException;
import java.io.FileReader;
import java.io.BufferedReader;

import java.util.List;
import java.util.ArrayList;

import net.i2p.I2PAppContext;
import net.i2p.client.streaming.IncomingConnectionFilter;

public class FilterFactory {
    public static IncomingConnectionFilter createFilter(I2PAppContext context, File definition) 
        throws IOException, InvalidDefinitionException {
        List<String> linesList = new ArrayList<String>();

        BufferedReader reader = null; 
        try {
            reader = new BufferedReader(new FileReader(definition));
            String line;
            while((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0)
                    continue;
                if (line.startsWith("#"))
                    continue;
                linesList.add(line);
            }
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ignored) {}
        }

        FilterDefinition parsedDefinition = DefinitionParser.parse(linesList.toArray(new String[0]));
        return new AccessFilter(context, parsedDefinition);
    }
}
