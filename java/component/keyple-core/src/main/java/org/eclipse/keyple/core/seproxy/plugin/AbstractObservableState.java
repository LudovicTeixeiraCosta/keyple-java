package org.eclipse.keyple.core.seproxy.plugin;

public abstract class AbstractObservableState {

    /** The states that the reader monitoring currentState machine can have */
    protected enum MonitoringState {
        WAIT_FOR_START_DETECTION, WAIT_FOR_SE_INSERTION, WAIT_FOR_SE_PROCESSING, WAIT_FOR_SE_REMOVAL
    }

    /* Identifier of the currentState */
    protected MonitoringState state;

    /* Reference to Reader */
    protected AbstractObservableLocalReader reader;

    /**
     * Create a new currentState with a currentState identifier
     * @param reader : observable reader this currentState is attached to
     * @param state : name of the currentState
     */
    protected AbstractObservableState(MonitoringState state,AbstractObservableLocalReader reader){
        this.reader = reader;
        this.state = state;
    }

    /**
     * Get currentState identifier
     * @return name currentState
     */
    public MonitoringState getMonitoringState(){
        return state;
    }


    protected abstract void onEvent(AbstractObservableLocalReader.StateEvent event);

    /**
     * Handle Start Detection Event
     * @return next currentState
    protected abstract void onStartDetection();
     */

    /**
     * Handle Stop Detection Event
     * @return next currentState
    protected abstract void onStopDetection();
     */

    /**
     * Handle Se Inserted Event
     * @return next currentState
    protected abstract void onSeInserted();
     */

    /**
     * Handle Se Processed Event
     * @return next currentState
    protected abstract void onSeProcessed();
     */

    /**
     * Handle Se Removed Event
     * @return next currentState
    protected abstract void onSeRemoved();
     */

    protected abstract void activate();
    protected abstract void deActivate();



}

