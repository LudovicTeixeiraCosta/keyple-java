/********************************************************************************
 * Copyright (c) 2019 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.keyple.calypso.transaction;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.keyple.calypso.command.po.AbstractPoCommandBuilder;
import org.eclipse.keyple.core.command.AbstractApduResponseParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * The PO command manager handles the PoCommand list updated by the "prepare" methods of
 * PoTransaction. It is used to keep builders and parsers between the time the commands are created
 * and the time their responses are parsed.
 * <p>
 * A flag (preparedCommandsProcessed) is used to manage the reset of the command list. It allows the
 * builders to be kept until the application creates a new list of commands. This flag is reset by
 * calling the method notifyCommandsProcessed.
 */
class PoCommandManager {
    /* logger */
    private static final Logger logger = LoggerFactory.getLogger(PoCommandManager.class);

    /** The list to contain the prepared commands and their parsers */
    private final List<PoCommand> poCommandList = new ArrayList<PoCommand>();
    /** The command index, incremented each time a command is added */
    private int preparedCommandIndex;
    private boolean preparedCommandsProcessed;
    private boolean secureSessionIsOpen;

    PoCommandManager() {
        preparedCommandsProcessed = true;
        secureSessionIsOpen = false;
    }

    /**
     * Resets the list of builders/parsers (only if it has already been processed).
     * <p>
     * Clears the processed flag.
     */
    private void updateBuilderParserList() {
        if (preparedCommandsProcessed) {
            poCommandList.clear();
            preparedCommandIndex = 0;
            preparedCommandsProcessed = false;
        }
    }


    /**
     * Indicates whether a secure session is open or not.
     * 
     * @param secureSessionIsOpen true if a secure session is open
     */
    public void setSecureSessionIsOpen(boolean secureSessionIsOpen) {
        this.secureSessionIsOpen = secureSessionIsOpen;
    }

    /**
     * Add a regular command (all but the StoredValue commands) to the builders and parsers list.
     * <p>
     * Handle the clearing of the list if needed.
     *
     * @param commandBuilder the command builder
     * @return the index to retrieve the parser later
     */
    int addRegularCommand(AbstractPoCommandBuilder commandBuilder) {
        /**
         * Reset the list if when preparing the first command after the last processing.
         * <p>
         * However, the parsers have remained available until now.
         */
        updateBuilderParserList();

        poCommandList.add(new PoCommand(commandBuilder));
        /* return and post-increment index */
        preparedCommandIndex++;
        return (preparedCommandIndex - 1);
    }

    /**
     * Informs that the commands have been processed.
     * <p>
     * Just record the information. The initialization of the list of commands will be done only the
     * next time a command is added, this allows access to the parsers contained in the list..
     */
    void notifyCommandsProcessed() {
        preparedCommandsProcessed = true;
    }

    /**
     * @return the current PoCommand list
     */
    public List<PoCommand> getPoCommandList() {
        /* here we make sure to clear the list if it has already been processed */
        updateBuilderParserList();
        return poCommandList;
    }

    /**
     * Returns the parser positioned at the indicated index
     * 
     * @param commandIndex the index of the wanted parser
     * @return the parser
     */
    public AbstractApduResponseParser getResponseParser(int commandIndex) {
        if (commandIndex < 0 || commandIndex >= poCommandList.size()) {
            throw new IllegalArgumentException(
                    String.format("Bad command index: index = %d, number of commands = %d",
                            commandIndex, poCommandList.size()));
        }
        return poCommandList.get(commandIndex).getResponseParser();
    }
}
