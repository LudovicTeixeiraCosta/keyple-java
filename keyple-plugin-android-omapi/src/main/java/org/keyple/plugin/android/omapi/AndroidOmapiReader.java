/*
 * Copyright (c) 2018 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * All rights reserved. This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License version 2.0 which accompanies this distribution, and is
 * available at https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 */

package org.keyple.plugin.android.omapi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.keyple.seproxy.ApduRequest;
import org.keyple.seproxy.ApduResponse;
import org.keyple.seproxy.ByteBufferUtils;
import org.keyple.seproxy.ProxyReader;
import org.keyple.seproxy.SeRequest;
import org.keyple.seproxy.SeResponse;
import org.keyple.seproxy.exceptions.IOReaderException;
import org.simalliance.openmobileapi.Channel;
import org.simalliance.openmobileapi.Reader;
import org.simalliance.openmobileapi.Session;
import android.util.Log;


public class AndroidOmapiReader implements ProxyReader {


    private static final String TAG = AndroidOmapiReader.class.getSimpleName();

    private Reader omapiReader;
    private Channel channel = null;


    protected AndroidOmapiReader(Reader omapiReader) {
        this.omapiReader = omapiReader;
    }

    @Override
    public String getName() {
        return omapiReader.getName();
    }

    @Override
    public SeResponse transmit(SeRequest seApplicationRequest) throws IOReaderException {

        Log.i(TAG, "Create Session from reader...");
        Session session = null;
        SeResponse seResponse = null;
        ApduResponse fci = null;

        try {
            Log.i(TAG, "Create session...");
            session = omapiReader.openSession();

            Log.i(TAG, "Create logical channel within the session...");
            channel = session.openLogicalChannel(
                    ByteBufferUtils.toBytes(seApplicationRequest.getAidToSelect()));
            fci = new ApduResponse(ByteBuffer.wrap(channel.getSelectResponse()), true);

        } catch (Exception e) {
            throw new IOReaderException(e.getMessage(), e.getCause());
        }

        Log.i(TAG, "Send APDU commands from SeRequest objects");
        List<ApduResponse> apduResponses = new ArrayList<ApduResponse>();
        for (ApduRequest seRequest : seApplicationRequest.getApduRequests()) {
            byte[] respApdu = new byte[0];
            try {
                respApdu = channel.transmit(ByteBufferUtils.toBytes(seRequest.getBuffer()));
                apduResponses.add(new ApduResponse(respApdu, true));
            } catch (IOException e) {
                e.printStackTrace();
                apduResponses.add(new ApduResponse(ByteBuffer.allocate(0), false));
            }
        }

        seResponse = new SeResponse(false, fci, apduResponses);

        if (!seApplicationRequest.keepChannelOpen()) {
            channel.close();
        }

        return seResponse;
    }

    @Override
    public boolean isSEPresent() throws IOReaderException {
        return omapiReader.isSecureElementPresent();
    }

}