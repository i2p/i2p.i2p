package net.i2p.i2ptunnel.access;

class FilterDefinition {

    private final Threshold defaultThreshold;
    private final FilterDefinitionElement[] elements;
    private final Recorder[] recorders;
    private final int purgeMinutes;

    FilterDefinition(Threshold defaultThreshold,
                        FilterDefinitionElement[] elements,
                        Recorder[] recorders) {
        this.defaultThreshold = defaultThreshold;
        this.elements = elements;
        this.recorders = recorders;

        int maxMinutes = defaultThreshold.getMinutes();
        for (FilterDefinitionElement element : elements) 
            maxMinutes = Math.max(maxMinutes, element.getThreshold().getMinutes());
        for (Recorder recorder : recorders) 
            maxMinutes = Math.max(maxMinutes, recorder.getThreshold().getMinutes());

        this.purgeMinutes = maxMinutes;
    }

    Threshold getDefaultThreshold() {
        return defaultThreshold;
    }

    FilterDefinitionElement[] getElements() {
        return elements;
    }

    Recorder[] getRecorders() {
        return recorders;
    }

    int getPurgeMinutes() {
        return purgeMinutes;
    }
}
