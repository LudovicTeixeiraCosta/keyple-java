/********************************************************************************
 * Copyright (c) 2018 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.keyple.plugin.stub;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import org.eclipse.keyple.core.seproxy.SeReader;
import org.eclipse.keyple.core.seproxy.exception.KeypleReaderException;
import org.eclipse.keyple.core.seproxy.exception.KeypleReaderNotFoundException;
import org.eclipse.keyple.core.seproxy.plugin.AbstractThreadedObservablePlugin;
import org.eclipse.keyple.core.seproxy.protocol.TransmissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This plugin allows to simulate Secure Element communication by creating @{@link StubReaderImpl}
 * and @{@link StubSecureElement}. Plug a new StubReader with StubPlugin#plugStubReader and insert
 * an implementation of your own of {@link StubSecureElement} to start simulation communication.
 * This class is a singleton, use StubPlugin#getInstance to access it
 *
 */
final class StubPluginImpl extends AbstractThreadedObservablePlugin implements StubPlugin {

    // private static final StubPlugin uniqueInstance = new StubPlugin();

    private static final Logger logger = LoggerFactory.getLogger(StubPluginImpl.class);

    private final Map<String, String> parameters = new HashMap<String, String>();

    // simulated list of real-time connected stubReader
    private SortedSet<String> connectedStubNames =
            Collections.synchronizedSortedSet(new ConcurrentSkipListSet<String>());

    StubPluginImpl() {
        super(PLUGIN_NAME);

        /*
         * Monitoring is not handled by a lower layer (as in PC/SC), reduce the threading period to
         * 10 ms to speed up responsiveness.
         */
        threadWaitTimeout = 10;
    }

    /**
     * Gets the single instance of StubPlugin.
     *
     * @return single instance of StubPlugin
     */
    /*
     * public static StubPlugin getInstance() { return uniqueInstance; }
     */

    @Override
    public Map<String, String> getParameters() {
        return parameters;
    }

    @Override
    public void setParameter(String key, String value) {
        parameters.put(key, value);
    }

    public void plugStubReader(String name, Boolean synchronous) {
        plugStubReader(name, TransmissionMode.CONTACTLESS, synchronous);
    }

    public void plugStubReader(String name, TransmissionMode transmissionMode,
            Boolean synchronous) {

        logger.info("Plugging a new reader with name " + name);
        /* add the native reader to the native readers list */
        Boolean exist = connectedStubNames.contains(name);

        if (!exist && synchronous) {
            /* add the reader as a new reader to the readers list */
            readers.add(new StubReaderImpl(name));
        }

        connectedStubNames.add(name);

        if (exist) {
            logger.error("Reader with name " + name + " was already plugged");
        }

    }

    public void plugStubReaders(Set<String> names, Boolean synchronous) {
        logger.debug("Plugging {} readers ..", names.size());

        /* plug stub readers that were not plugged already */
        // duplicate names
        Set<String> newNames = new HashSet<String>(names);
        // remove already connected stubNames
        newNames.removeAll(connectedStubNames);

        logger.debug("New readers to be created #{}", newNames.size());


        /*
         * Add new names to the connectedStubNames
         */

        if (newNames.size() > 0) {
            if (synchronous) {
                List<StubReaderImpl> newReaders = new ArrayList<StubReaderImpl>();
                for (String name : newNames) {
                    newReaders.add(new StubReaderImpl(name));
                }
                readers.addAll(newReaders);
            }

            connectedStubNames.addAll(names);

        } else {
            logger.error("All {} readers were already plugged", names.size());

        }


    }

    public void unplugStubReader(String name, Boolean synchronous) throws KeypleReaderException {

        if (!connectedStubNames.contains(name)) {
            logger.warn("unplugStubReader() No reader found with name {}", name);
        } else {
            /* remove the reader from the readers list */
            if (synchronous) {
                connectedStubNames.remove(name);
                readers.remove(getReader(name));
            } else {
                connectedStubNames.remove(name);
            }
            /* remove the native reader from the native readers list */
            logger.info("Unplugged reader with name {}, connectedStubNames size {}", name,
                    connectedStubNames.size());
        }
    }

    public void unplugStubReaders(Set<String> names, Boolean synchronous) {
        logger.info("Unplug {} stub readers", names.size());
        logger.debug("Unplug stub readers.. {}", names);
        List<StubReaderImpl> readersToDelete = new ArrayList<StubReaderImpl>();
        for (String name : names) {
            try {
                readersToDelete.add((StubReaderImpl) getReader(name));
            } catch (KeypleReaderNotFoundException e) {
                logger.warn("unplugStubReaders() No reader found with name {}", name);
            }
        }
        connectedStubNames.removeAll(names);
        if (synchronous) {
            readers.removeAll(readersToDelete);
        }
    }


    /**
     * Fetch the list of connected native reader (from a simulated list) and returns their names (or
     * id)
     *
     * @return connected readers' name list
     */
    @Override
    protected SortedSet<String> fetchNativeReadersNames() {
        if (connectedStubNames.isEmpty()) {
            logger.trace("No reader available.");
        }
        return connectedStubNames;
    }

    /**
     * Init native Readers to empty Set
     * 
     * @return the list of SeReader objects.
     * @throws KeypleReaderException if a reader error occurs
     */
    @Override
    protected SortedSet<SeReader> initNativeReaders() throws KeypleReaderException {
        /* init Stub Readers response object */
        SortedSet<SeReader> newNativeReaders = new ConcurrentSkipListSet<SeReader>();

        /*
         * parse the current readers list to create the ProxyReader(s) associated with new reader(s)
         * if (connectedStubNames != null && connectedStubNames.size() > 0) { for (String name :
         * connectedStubNames) { newNativeReaders.add(new StubReader(name)); } }
         */
        return newNativeReaders;
    }

    /**
     * Fetch the reader whose name is provided as an argument. Returns the current reader if it is
     * already listed. Creates and returns a new reader if not.
     *
     * Throws an exception if the wanted reader is not found.
     *
     * @param name name of the reader
     * @return the reader object
     */
    @Override
    protected SeReader fetchNativeReader(String name) {
        for (SeReader reader : readers) {
            if (reader.getName().equals(name)) {
                return reader;
            }
        }
        SeReader reader = null;
        if (connectedStubNames.contains(name)) {
            reader = new StubReaderImpl(name);
        }
        return reader;
    }
}
