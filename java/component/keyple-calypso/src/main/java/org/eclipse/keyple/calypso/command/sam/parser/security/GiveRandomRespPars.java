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
package org.eclipse.keyple.calypso.command.sam.parser.security;


import org.eclipse.keyple.calypso.command.sam.AbstractSamResponseParser;
import org.eclipse.keyple.core.seproxy.message.ApduResponse;

/**
 * PO Give Random response parser.
 * <p>
 * No output data except status word
 */
public class GiveRandomRespPars extends AbstractSamResponseParser {
    /**
     * Instantiates a new GiveRandomRespPars.
     *
     * @param response the response
     */
    public GiveRandomRespPars(ApduResponse response) {
        super(response);
    }
}
