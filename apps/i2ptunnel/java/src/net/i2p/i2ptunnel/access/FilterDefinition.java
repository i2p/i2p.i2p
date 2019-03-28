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
    private final int purgeMinutes;

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
