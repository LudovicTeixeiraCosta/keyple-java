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

import java.util.HashMap;
import java.util.Map;
import org.eclipse.keyple.calypso.command.sam.AbstractSamResponseParser;
import org.eclipse.keyple.core.seproxy.message.ApduResponse;

public class SvPrepareOperationRespPars extends AbstractSamResponseParser {
    private static final Map<Integer, StatusProperties> STATUS_TABLE;

    static {
        Map<Integer, StatusProperties> m =
                new HashMap<Integer, StatusProperties>(AbstractSamResponseParser.STATUS_TABLE);
        m.put(0x6700, new StatusProperties(false, "Lc value not supported"));
        m.put(0x6985, new StatusProperties(false, "Preconditions not satisfied"));
        m.put(0x6A00, new StatusProperties(false, "Incorrect P1 or P2"));
        m.put(0x6A80, new StatusProperties(false,
                "Incorrect incoming data: unknown or incorrect format"));
        m.put(0x6A83, new StatusProperties(false,
                "Record not found: ciphering key or key to cipher not found"));
        m.put(0x9000, new StatusProperties(true, "Successful execution."));
        STATUS_TABLE = m;
    }

    @Override
    protected Map<Integer, StatusProperties> getStatusTable() {
        return STATUS_TABLE;
    }

    /**
     * Instantiates a new SvPrepareOperationRespPars.
     *
     * @param response from the SAM
     */
    public SvPrepareOperationRespPars(ApduResponse response) {
        super(response);
    }

}
