package net.i2p.i2ptunnel.access;

/**
 * Definition of an access filter.
 *
 * This POJO contains the parsed representation from the filter definition file.
 *
 * @since 0.9.40
 */
class FilterDefinition {

    private final Threshold defaultThreshold;
    private final FilterDefinitionElement[] elements;
    private final Recorder[] recorders;
    private final int purgeSeconds;

    /**
     * @param defaultThreshold threshold to apply to unknown remote destinations
     * @param elements the elements defined in the filter definition, if any
     * @param recorders the recorders defined in the filter definition, if any
     */
    FilterDefinition(Threshold defaultThreshold,
                        FilterDefinitionElement[] elements,
                        Recorder[] recorders) {
        this.defaultThreshold = defaultThreshold;
        this.elements = elements;
        this.recorders = recorders;

        int maxSeconds = defaultThreshold.getSeconds();
        for (FilterDefinitionElement element : elements) 
            maxSeconds = Math.max(maxSeconds, element.getThreshold().getSeconds());
        for (Recorder recorder : recorders) 
            maxSeconds = Math.max(maxSeconds, recorder.getThreshold().getSeconds());

        this.purgeSeconds = maxSeconds;
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

    int getPurgeSeconds() {
        return purgeSeconds;
    }
}
