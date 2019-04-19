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
package org.eclipse.keyple.integration.calypso;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.regex.Pattern;
import org.eclipse.keyple.calypso.transaction.CalypsoSam;
import org.eclipse.keyple.calypso.transaction.PoSelectionRequest;
import org.eclipse.keyple.calypso.transaction.SamResource;
import org.eclipse.keyple.calypso.transaction.SamSelectionRequest;
import org.eclipse.keyple.core.seproxy.*;
import org.eclipse.keyple.core.seproxy.exception.KeypleBaseException;
import org.eclipse.keyple.core.seproxy.exception.KeypleReaderException;
import org.eclipse.keyple.core.seproxy.exception.NoStackTraceThrowable;
import org.eclipse.keyple.core.seproxy.protocol.Protocol;
import org.eclipse.keyple.core.seproxy.protocol.SeProtocolSetting;
import org.eclipse.keyple.core.transaction.MatchingSelection;
import org.eclipse.keyple.core.transaction.SeSelection;
import org.eclipse.keyple.core.transaction.SeSelectionRequest;
import org.eclipse.keyple.core.util.ByteArrayUtil;
import org.eclipse.keyple.plugin.pcsc.PcscPlugin;
import org.eclipse.keyple.plugin.pcsc.PcscProtocolSetting;
import org.eclipse.keyple.plugin.pcsc.PcscReader;

public class TestEngine {

    public static SeReader poReader;
    public static SamResource samResource;

    public static PoFileStructureInfo selectPO()
            throws IllegalArgumentException, KeypleReaderException {

        SeSelection seSelection = new SeSelection();

        // Add Audit C0 AID to the list
        seSelection
                .prepareSelection(new PoSelectionRequest(new SeSelector(
                        new SeSelector.AidSelector(
                                ByteArrayUtil.fromHex(PoFileStructureInfo.poAuditC0Aid), null),
                        null, "Audit C0"), ChannelState.KEEP_OPEN, Protocol.ANY));

        // Add CLAP AID to the list
        seSelection
                .prepareSelection(new PoSelectionRequest(new SeSelector(
                        new SeSelector.AidSelector(
                                ByteArrayUtil.fromHex(PoFileStructureInfo.clapAid), null),
                        null, "CLAP"), ChannelState.KEEP_OPEN, Protocol.ANY));

        // Add cdLight AID to the list
        seSelection
                .prepareSelection(new PoSelectionRequest(new SeSelector(
                        new SeSelector.AidSelector(
                                ByteArrayUtil.fromHex(PoFileStructureInfo.cdLightAid), null),
                        null, "CDLight"), ChannelState.KEEP_OPEN, Protocol.ANY));

        MatchingSelection matchingSelection =
                seSelection.processExplicitSelection(poReader).getActiveSelection();
        if (matchingSelection != null && matchingSelection.getMatchingSe().isSelected()) {
            return new PoFileStructureInfo(matchingSelection.getMatchingSe());
        }

        throw new IllegalArgumentException("No recognizable PO detected.");
    }

    private static SeReader getReader(SeProxyService seProxyService, String pattern)
            throws KeypleReaderException {

        Pattern p = Pattern.compile(pattern);
        for (ReaderPlugin plugin : seProxyService.getPlugins()) {
            for (SeReader reader : plugin.getReaders()) {
                if (p.matcher(reader.getName()).matches()) {
                    return reader;
                }
            }
        }
        return null;
    }

    public static void configureReaders()
            throws IOException, InterruptedException, KeypleBaseException {

        SeProxyService seProxyService = SeProxyService.getInstance();
        SortedSet<ReaderPlugin> pluginsSet = new ConcurrentSkipListSet<ReaderPlugin>();
        pluginsSet.add(PcscPlugin.getInstance());
        seProxyService.setPlugins(pluginsSet);

        final String PO_READER_NAME_REGEX = ".*(ASK|ACS).*";
        final String SAM_READER_NAME_REGEX = ".*(Cherry TC|SCM Microsystems|Identive|HID).*";

        poReader = getReader(seProxyService, PO_READER_NAME_REGEX);
        SeReader samReader = getReader(seProxyService, SAM_READER_NAME_REGEX);


        if (poReader == samReader || poReader == null || samReader == null) {
            throw new IllegalStateException("Bad PO/SAM setup");
        }

        System.out.println(
                "\n==================================================================================");
        System.out.println("PO Reader  : " + poReader.getName());
        System.out.println("SAM Reader : " + samReader.getName());
        System.out.println(
                "==================================================================================");

        poReader.setParameter(PcscReader.SETTING_KEY_PROTOCOL, PcscReader.SETTING_PROTOCOL_T1);
        samReader.setParameter(PcscReader.SETTING_KEY_PROTOCOL, PcscReader.SETTING_PROTOCOL_T0);

        // provide the reader with the map
        poReader.addSeProtocolSetting(
                new SeProtocolSetting(PcscProtocolSetting.SETTING_PROTOCOL_ISO14443_4));

        try {
            if (!samReader.isSePresent()) {
                throw new IllegalStateException("No SAM present in the reader.");
            }
        } catch (NoStackTraceThrowable noStackTraceThrowable) {
            throw new KeypleReaderException("Exception raised while checking SE presence.");
        }

        // operate PO multiselection
        final String SAM_ATR_REGEX = "3B3F9600805A[0-9a-fA-F]{2}80[0-9a-fA-F]{16}829000";

        // check the availability of the SAM, open its physical and logical channels and keep it
        // open
        SeSelection samSelection = new SeSelection();

        SeSelectionRequest samSelectionRequest = new SamSelectionRequest(
                new SeSelector(null, new SeSelector.AtrFilter(SAM_ATR_REGEX), "SAM Selection"),
                ChannelState.KEEP_OPEN, Protocol.ANY);

        /* Prepare selector, ignore MatchingSe here */
        samSelection.prepareSelection(samSelectionRequest);

        CalypsoSam calypsoSam;

        try {
            MatchingSelection matchingSelection =
                    samSelection.processExplicitSelection(samReader).getActiveSelection();
            if (matchingSelection != null) {
                calypsoSam = (CalypsoSam) matchingSelection.getMatchingSe();
                if (!calypsoSam.isSelected()) {
                    System.out.println("Unable to open a logical channel for SAM!");
                    throw new IllegalStateException("SAM channel opening failure");
                } else {
                }
            } else {
                System.out.println("The SAM selection returned null!");
                throw new IllegalStateException("SAM channel opening failure");
            }
        } catch (KeypleReaderException e) {
            throw new IllegalStateException("Reader exception: " + e.getMessage());
        }

        samResource = new SamResource(samReader, calypsoSam);
    }

}