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
package org.eclipse.keyple.calypso.command.sam.builder.security;

import org.eclipse.keyple.calypso.command.sam.AbstractSamCommandBuilder;
import org.eclipse.keyple.calypso.command.sam.CalypsoSamCommand;
import org.eclipse.keyple.calypso.command.sam.SamRevision;
import org.eclipse.keyple.calypso.command.sam.parser.security.SvCheckRespPars;
import org.eclipse.keyple.core.seproxy.message.ApduResponse;

/**
 * Builder for the SAM SV Check APDU command.
 */
public class SvCheckCmdBuild extends AbstractSamCommandBuilder {
    /** The command reference. */
    private static final CalypsoSamCommand command = CalypsoSamCommand.SV_CHECK;

    /**
     * Instantiates a new SvCheckCmdBuild to authenticate a card transaction.
     *
     * @param revision of the SAM
     * @param svPoSignature null if the operation is to abort the SV transaction, a 3 or 6-byte
     *        array containing the PO signature from SV Debit, SV Load or SV Undebit.
     */
    public SvCheckCmdBuild(SamRevision revision, byte[] svPoSignature) {
        super(command, null);
        if (svPoSignature != null && (svPoSignature.length != 3 && svPoSignature.length != 6)) {
            throw new IllegalArgumentException("Invalid svPoSignature.");
        }

        if (revision != null) {
            this.defaultRevision = revision;
        }

        byte cla = this.defaultRevision.getClassByte();
        byte p1 = (byte) 0x00;
        byte p2 = (byte) 0x00;

        if (svPoSignature != null) {
            // the operation is not "abort"
            byte[] data = new byte[svPoSignature.length];
            System.arraycopy(svPoSignature, 0, data, 0, svPoSignature.length);
            request = setApduRequest(cla, command, p1, p2, data, null);
        } else {
            request = setApduRequest(cla, command, p1, p2, null, (byte) 0x00);
        }

    }

    @Override
    public SvCheckRespPars createResponseParser(ApduResponse apduResponse) {
        return new SvCheckRespPars(apduResponse, this);
    }
}
