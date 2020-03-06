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
package org.eclipse.keyple.calypso.command.po.parser;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.keyple.calypso.command.po.AbstractPoResponseParser;
import org.eclipse.keyple.calypso.command.po.CalypsoPoCommands;
import org.eclipse.keyple.core.seproxy.message.ApduResponse;
import org.eclipse.keyple.core.seproxy.message.SeResponse;
import org.eclipse.keyple.core.seproxy.message.SelectionStatus;
import org.eclipse.keyple.core.util.ByteArrayUtil;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AppendRecordRespParsTest {

    @Test
    public void appendRecordRespPars() {
        List<ApduResponse> responses = new ArrayList<ApduResponse>();
        ApduResponse apduResponse = new ApduResponse(new byte[] {90, 0}, null);
        responses.add(apduResponse);
        SeResponse seResponse =
                new SeResponse(true, true,
                        new SelectionStatus(null,
                                new ApduResponse(ByteArrayUtil.fromHex("9000"), null), true),
                        responses);

        AbstractPoResponseParser apduResponseParser =
                new AppendRecordRespPars(seResponse.getApduResponses().get(0));
        Assert.assertEquals(CalypsoPoCommands.APPEND_RECORD, apduResponseParser.getCommand());
        Assert.assertArrayEquals(new byte[] {90, 0},
                apduResponseParser.getApduResponse().getBytes());
    }
}
