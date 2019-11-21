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
import org.eclipse.keyple.calypso.command.sam.CalypsoSamCommands;
import org.eclipse.keyple.calypso.command.sam.SamRevision;

/**
 * Builder for the SAM SV Check APDU command.
 */
public class SvCheckCmdBuild extends AbstractSamCommandBuilder {
    /** The command reference. */
    private static final CalypsoSamCommands command = CalypsoSamCommands.SV_CHECK;

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

        byte p1, p2;
        byte[] data;

        p1 = (byte) 0x00;
        p2 = (byte) 0x00;

        if (svPoSignature != null) {
            // the operation is not "abort"
            data = new byte[svPoSignature.length];
            System.arraycopy(svPoSignature, 0, data, 0, svPoSignature.length);
            request = setApduRequest(cla, command, p1, p2, data, null);
        } else {
            request = setApduRequest(cla, command, p1, p2, null, (byte) 0x00);
        }

    }
}
