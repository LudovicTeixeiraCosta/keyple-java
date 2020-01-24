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
package org.eclipse.keyple.calypso.transaction;

import java.util.*;
import org.eclipse.keyple.calypso.command.po.*;
import org.eclipse.keyple.calypso.command.po.builder.*;
import org.eclipse.keyple.calypso.command.po.builder.security.AbstractOpenSessionCmdBuild;
import org.eclipse.keyple.calypso.command.po.builder.security.CloseSessionCmdBuild;
import org.eclipse.keyple.calypso.command.po.builder.security.PinOperation;
import org.eclipse.keyple.calypso.command.po.builder.security.VerifyPinCmdBuild;
import org.eclipse.keyple.calypso.command.po.builder.storedvalue.SvDebitCmdBuild;
import org.eclipse.keyple.calypso.command.po.builder.storedvalue.SvGetCmdBuild;
import org.eclipse.keyple.calypso.command.po.builder.storedvalue.SvReloadCmdBuild;
import org.eclipse.keyple.calypso.command.po.builder.storedvalue.SvUndebitCmdBuild;
import org.eclipse.keyple.calypso.command.po.parser.*;
import org.eclipse.keyple.calypso.command.po.parser.security.AbstractOpenSessionRespPars;
import org.eclipse.keyple.calypso.command.po.parser.security.CloseSessionRespPars;
import org.eclipse.keyple.calypso.command.po.parser.security.VerifyPinRespPars;
import org.eclipse.keyple.calypso.command.po.parser.storedvalue.SvGetRespPars;
import org.eclipse.keyple.calypso.transaction.exception.*;
import org.eclipse.keyple.core.command.AbstractApduCommandBuilder;
import org.eclipse.keyple.core.command.AbstractApduResponseParser;
import org.eclipse.keyple.core.seproxy.ChannelControl;
import org.eclipse.keyple.core.seproxy.SeReader;
import org.eclipse.keyple.core.seproxy.exception.KeypleReaderException;
import org.eclipse.keyple.core.seproxy.message.*;
import org.eclipse.keyple.core.seproxy.message.ProxyReader;
import org.eclipse.keyple.core.seproxy.protocol.TransmissionMode;
import org.eclipse.keyple.core.util.ByteArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Portable Object Secure Session.
 *
 * A non-encrypted secure session with a Calypso PO requires the management of two
 * {@link ProxyReader} in order to communicate with both a Calypso PO and a SAM
 *
 * @author Calypso Networks Association
 */
public final class PoTransaction {

    /* private constants */
    private static final int OFFSET_CLA = 0;
    private static final int OFFSET_INS = 1;
    private static final int OFFSET_P1 = 2;
    private static final int OFFSET_P2 = 3;
    private static final int OFFSET_Lc = 4;
    private static final int OFFSET_DATA = 5;

    /** Ratification command APDU for rev <= 2.4 */
    private static final byte[] ratificationCmdApduLegacy = ByteArrayUtil.fromHex("94B2000000");
    /** Ratification command APDU for rev > 2.4 */
    private static final byte[] ratificationCmdApdu = ByteArrayUtil.fromHex("00B2000000");

    private static final Logger logger = LoggerFactory.getLogger(PoTransaction.class);

    /** The reader for PO. */
    private final ProxyReader poReader;
    /** The SAM commands processor */
    private SamCommandsProcessor samCommandsProcessor;
    /** The current CalypsoPo */
    private final CalypsoPo calypsoPo;
    /** the type of the notified event. */
    private SessionState sessionState;
    /** The PO Secure Session final status according to mutual authentication result */
    private boolean transactionResult;
    /** The previous PO Secure Session ratification status */
    private boolean wasRatified;
    /** The data read at opening */
    private byte[] openRecordDataRead;
    /** The current secure session modification mode: ATOMIC or MULTIPLE */
    private ModificationMode currentModificationMode;
    /** The current secure session access level: PERSO, RELOAD, DEBIT */
    private SessionAccessLevel currentAccessLevel;
    /* modifications counter management */
    private boolean modificationsCounterIsInBytes;
    private int modificationsCounterMax;
    private int modificationsCounter;

    private boolean svDoubleGet;

    private final PoCommandsManager poCommandsManager;
    private String lastError;

    /**
     * PoTransaction with PO and SAM readers.
     * <ul>
     * <li>Logical channels with PO &amp; SAM could already be established or not.</li>
     * <li>A list of SAM parameters is provided as en EnumMap.</li>
     * </ul>
     *
     * @param poResource the PO resource (combination of {@link SeReader} and {@link CalypsoPo})
     * @param samResource the SAM resource (combination of {@link SeReader} and {@link CalypsoSam})
     * @param securitySettings a list of security settings ({@link SecuritySettings}) used in the
     *        session (such as key identification)
     */
    public PoTransaction(PoResource poResource, SamResource samResource,
            SecuritySettings securitySettings) {

        this(poResource);

        samCommandsProcessor = new SamCommandsProcessor(samResource, poResource, securitySettings);
    }

    /**
     * PoTransaction with PO reader and without SAM reader.
     * <ul>
     * <li>Logical channels with PO could already be established or not.</li>
     * </ul>
     *
     * @param poResource the PO resource (combination of {@link SeReader} and {@link CalypsoPo})
     */
    public PoTransaction(PoResource poResource) {
        this.poReader = (ProxyReader) poResource.getSeReader();

        this.calypsoPo = poResource.getMatchingSe();

        modificationsCounterIsInBytes = calypsoPo.isModificationsCounterInBytes();

        modificationsCounterMax = modificationsCounter = calypsoPo.getModificationsCounter();

        sessionState = SessionState.SESSION_UNINITIALIZED;

        poCommandsManager = new PoCommandsManager();

        transactionResult = true;

        setLastError("No error");
    }

    /**
     * Open a Secure Session.
     * <ul>
     * <li>The PO must have been previously selected, so a logical channel with the PO application
     * must be already active.</li>
     * <li>The PO serial &amp; revision are identified from FCI data.</li>
     * <li>A first request is sent to the SAM session reader.
     * <ul>
     * <li>In case not logical channel is active with the SAM, a channel is open.</li>
     * <li>Then a Select Diversifier (with the PO serial) &amp; a Get Challenge are automatically
     * operated. The SAM challenge is recovered.</li>
     * </ul>
     * </li>
     * <li>The PO Open Session command is built according to the PO revision, the SAM challenge, the
     * keyIndex, and openingSfiToSelect / openingRecordNumberToRead.</li>
     * <li>Next the PO reader is requested:
     * <ul>
     * <li>for the current selected PO AID, with channelControl set to KEEP_OPEN,</li>
     * <li>and some PO Apdu Requests including at least the Open Session command and optionally some
     * PO command to operate inside the session.</li>
     * </ul>
     * </li>
     * <li>The session PO keyset reference is identified from the PO Open Session response, the PO
     * challenge is recovered too.</li>
     * <li>According to the PO responses of Open Session and the PO commands sent inside the
     * session, a "cache" of SAM commands is filled with the corresponding Digest Init &amp; Digest
     * Update commands.</li>
     * <li>Returns the corresponding PO SeResponse (responses to poBuilderParsers).</li>
     * </ul>
     *
     * @param accessLevel access level of the session (personalization, load or debit).
     * @param openingSfiToSelect SFI of the file to select (0 means no file to select)
     * @param openingRecordNumberToRead number of the record to read
     * @param poBuilderParsers the po commands inside session
     * @return SeResponse response to all executed commands including the self generated "Open
     *         Secure Session" command
     * @throws KeypleReaderException the IO reader exception
     */
    private SeResponse processAtomicOpening(SessionAccessLevel accessLevel, byte openingSfiToSelect,
            byte openingRecordNumberToRead, List<PoBuilderParser> poBuilderParsers)
            throws KeypleReaderException {
        int splitCommandIndex;
        // gets the terminal challenge
        byte[] sessionTerminalChallenge = samCommandsProcessor.getSessionTerminalChallenge();

        /* PO ApduRequest List to hold Open Secure Session and other optional commands */
        List<ApduRequest> poApduRequestList = new ArrayList<ApduRequest>();

        /* Build the PO Open Secure Session command */
        // TODO decide how to define the extraInfo field. Empty for the moment.
        AbstractOpenSessionCmdBuild poOpenSession = AbstractOpenSessionCmdBuild.create(
                calypsoPo.getRevision(), accessLevel.getSessionKey(), sessionTerminalChallenge,
                openingSfiToSelect, openingRecordNumberToRead, "");

        /* Add the resulting ApduRequest to the PO ApduRequest list */
        poApduRequestList.add(poOpenSession.getApduRequest());

        /*
         * Add all optional PoSendableInSession commands to the PO ApduRequest list, get the
         * SplitCommandIndex (should be -1 if no split is needed)
         */
        splitCommandIndex = buildApduRequests(poBuilderParsers, poApduRequestList);

        /* Create a SeRequest from the ApduRequest list, PO AID as Selector, keep channel open */
        SeRequest poSeRequest = new SeRequest(poApduRequestList);

        logger.trace("processAtomicOpening => opening:  POSEREQUEST = {}", poSeRequest);

        /* Transmit the commands to the PO */
        SeResponse poSeResponse = poReader.transmit(poSeRequest);

        logger.trace("processAtomicOpening => opening:  POSERESPONSE = {}", poSeResponse);

        if (poSeResponse == null) {
            throw new KeypleCalypsoSecureSessionException("Null response received",
                    KeypleCalypsoSecureSessionException.Type.PO, poSeRequest.getApduRequests(),
                    null);
        }

        if (!poSeResponse.wasChannelPreviouslyOpen()) {
            throw new KeypleCalypsoSecureSessionException("The logical channel was not open",
                    KeypleCalypsoSecureSessionException.Type.PO, poSeRequest.getApduRequests(),
                    null);
        }

        /* Retrieve and check the ApduResponses */
        List<ApduResponse> poApduResponseList = poSeResponse.getApduResponses();

        /* Do some basic checks */
        if (poApduRequestList.size() != poApduResponseList.size()) {
            throw new KeypleCalypsoSecureSessionException("Inconsistent requests and responses",
                    KeypleCalypsoSecureSessionException.Type.PO, poApduRequestList,
                    poApduResponseList);
        }

        /* Track Read Records for later use to build anticipated responses. */
        AnticipatedResponseBuilder.storeCommandResponse(poBuilderParsers, poApduRequestList,
                poApduResponseList, 1,
                splitCommandIndex >= 0 ? poBuilderParsers.size() - 1 : splitCommandIndex);

        /* Parse the response to Open Secure Session (the first item of poApduResponseList) */
        AbstractOpenSessionRespPars poOpenSessionPars = AbstractOpenSessionRespPars
                .create(poApduResponseList.get(0), calypsoPo.getRevision());
        byte[] sessionCardChallenge = poOpenSessionPars.getPoChallenge();

        /* Build the Digest Init command from PO Open Session */
        /** The PO KIF */
        byte poKif = poOpenSessionPars.getSelectedKif();
        /** The PO KVC */
        // TODO handle rev 1 KVC (provided in the response to select DF. CalypsoPo?)
        byte poKvc = poOpenSessionPars.getSelectedKvc();

        if (logger.isDebugEnabled()) {
            logger.debug(
                    "processAtomicOpening => opening: CARDCHALLENGE = {}, POKIF = {}, POKVC = {}",
                    ByteArrayUtil.toHex(sessionCardChallenge), String.format("%02X", poKif),
                    String.format("%02X", poKvc));
        }

        if (!samCommandsProcessor.isAuthorizedKvc(poKvc)) {
            throw new KeypleCalypsoSecureSessionUnauthorizedKvcException(
                    String.format("PO KVC = %02X", poKvc));
        }

        /* Keep the ratification status and read data */
        wasRatified = poOpenSessionPars.wasRatified();
        openRecordDataRead = poOpenSessionPars.getRecordDataRead();

        /*
         * Initialize the digest processor. It will store all digest operations (Digest Init, Digest
         * Update) until the session closing. At this moment, all SAM Apdu will be processed at
         * once.
         */
        samCommandsProcessor.initializeDigester(accessLevel, false, false,
                SecuritySettings.DefaultKeyInfo.SAM_DEFAULT_KEY_RECORD_NUMBER, poKif, poKvc,
                poApduResponseList.get(0).getDataOut());

        /*
         * Add all commands data to the digest computation. The first command in the list is the
         * open secure session command. This command is not included in the digest computation, so
         * we skip it and start the loop at index 1.
         */
        if ((poBuilderParsers != null) && !poBuilderParsers.isEmpty()) {

            for (int i = 1; i < poApduRequestList.size(); i++) { // The loop starts after the Open
                /*
                 * Add requests and responses to the digest processor
                 */
                samCommandsProcessor.pushPoExchangeData(poApduRequestList.get(i),
                        poApduResponseList.get(i));
            }
        }

        /* Remove Open Secure Session response and create a new SeResponse */
        poApduResponseList.remove(0);

        /* Specific processing if the request has been split */
        while (splitCommandIndex >= 0) {
            /* Execute a loop, since the request can be split several times. */
            /* Determine the command that made the request split */
            PoBuilderParser.SplitCommandInfo splitCommandInfo =
                    poBuilderParsers.get(splitCommandIndex).getSplitCommandInfo();
            switch (splitCommandInfo) {
                case VERIFY_PIN:
                    logger.debug("VERIFY PIN split  found!");
                    /*
                     * we expect here that the last received response is the answer to a Get
                     * Challenge command
                     */
                    byte[] poChallenge =
                            poApduResponseList.get(poApduResponseList.size() - 1).getDataOut();
                    /* Remove this PoTransaction internal response from the received list */
                    poApduResponseList.remove(poApduResponseList.size() - 1);
                    /* Retrieve the Verify Pin partially built command */
                    VerifyPinCmdBuild verifyPinCmdBuild = (VerifyPinCmdBuild) poBuilderParsers
                            .get(splitCommandIndex + 1).getCommandBuilder();
                    /* Get the encrypted PIN with the help of the SAM */
                    byte[] pinCipheredData = samCommandsProcessor.getCipheredPinData(poChallenge,
                            verifyPinCmdBuild.getPin(), null);
                    /* Complete the Verify Pin command builder */
                    verifyPinCmdBuild.setCipheredPinData(pinCipheredData);
                    /* Remove the Get Challenge command from the builder list (internal command) */
                    poBuilderParsers.remove(splitCommandIndex);
                    /* Clear the request list to prepare the following transmission */
                    poApduRequestList.clear();
                    /* Get the next ApduRequest list and keep the possible split index */
                    int newSplitCommandIndex =
                            buildApduRequests(poBuilderParsers, poApduRequestList);
                    /* Create a SeRequest from the ApduRequest list */
                    poSeRequest = new SeRequest(poApduRequestList);
                    /* Transmit the commands to the PO and get the responses */
                    SeResponse poSeResponseVP = poReader.transmit(poSeRequest);
                    List<ApduResponse> poApduResponseListVP = poSeResponseVP.getApduResponses();
                    /* Add requests and responses to the digest processor */
                    for (int i = 0; i < poApduRequestList.size(); i++) {
                        samCommandsProcessor.pushPoExchangeData(poApduRequestList.get(i),
                                poApduResponseListVP.get(i));
                    }
                    /* Track Read Record commands for later use to build anticipated responses. */
                    AnticipatedResponseBuilder.storeCommandResponse(poBuilderParsers,
                            poApduRequestList, poApduResponseListVP, splitCommandIndex,
                            splitCommandIndex + poApduResponseListVP.size() - 1);
                    /* Append response to the output response list */
                    poApduResponseList.addAll(poApduResponseListVP);
                    /* update the splitCommandIndex and loop */
                    splitCommandIndex = newSplitCommandIndex;
                    break;
                case NOT_SET:
                default:
                    throw new IllegalStateException("Unexpected SeRequest split");
            }
        }

        sessionState = SessionState.SESSION_OPEN;

        return new SeResponse(true, true, poSeResponse.getSelectionStatus(), poApduResponseList);
    }

    /**
     * Build an ApduRequest List from command list (PoBuilderParser).
     * <p>
     * When a "split" command is found, the process stops and the method returns the index of the
     * split command, if not -1 is returned.
     * <p>
     * If the {@link PoBuilderParser} list is null or empty, nothing is added to the
     * {@link ApduRequest} list and returned split command index is -1
     * <p>
     * All processed commands (placed before a split command) are set as sent.
     * 
     * @param poBuilderParsers a po commands list to be sent in session
     * @param apduRequestList the list of the next Apdu requests to send to the PO
     * @return the index of the split command in the initial list
     */
    private int buildApduRequests(List<PoBuilderParser> poBuilderParsers,
            List<ApduRequest> apduRequestList) {
        if (poBuilderParsers != null) {
            for (int i = 0; i < poBuilderParsers.size(); i++) {
                PoBuilderParser poBuilderParser = poBuilderParsers.get(i);
                if (!poBuilderParser.isSent()) {
                    apduRequestList.add(poBuilderParser.getCommandBuilder().getApduRequest());
                    poBuilderParser.setSent();
                    if (poBuilderParser.isSplitCommand()) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Process PO commands in a Secure Session.
     * <ul>
     * <li>On the PO reader, generates a SeRequest with channelControl set to KEEP_OPEN, and
     * ApduRequests with the PO commands.</li>
     * <li>In case the secure session is active, the "cache" of SAM commands is completed with the
     * corresponding Digest Update commands.</li>
     * <li>If a session is open and channelControl is set to CLOSE_AFTER, the current PO session is
     * aborted</li>
     * <li>Returns the corresponding PO SeResponse.</li>
     * </ul>
     *
     * @param poBuilderParsers the po commands inside session
     * @param channelControl indicated if the SE channel of the PO reader must be closed after the
     *        last command
     * @return SeResponse all responses to the provided commands
     *
     * @throws KeypleReaderException IO Reader exception
     */
    private SeResponse processAtomicPoCommands(List<PoBuilderParser> poBuilderParsers,
            ChannelControl channelControl) throws KeypleReaderException {

        // Get PO ApduRequest List from PoSendableInSession List
        List<ApduRequest> poApduRequestList = new ArrayList<ApduRequest>();
        int splitCommandIndex = buildApduRequests(poBuilderParsers, poApduRequestList);

        /*
         * Create a SeRequest from the ApduRequest list, PO AID as Selector, manage the logical
         * channel according to the channelControl enum
         */
        SeRequest poSeRequest = new SeRequest(poApduRequestList);

        logger.trace("processAtomicPoCommands => POREQUEST = {}", poSeRequest);

        /* Transmit the commands to the PO */
        SeResponse poSeResponse = poReader.transmit(poSeRequest, channelControl);

        logger.trace("processAtomicPoCommands => PORESPONSE = {}", poSeResponse);

        if (poSeResponse == null) {
            throw new KeypleCalypsoSecureSessionException("Null response received",
                    KeypleCalypsoSecureSessionException.Type.PO, poSeRequest.getApduRequests(),
                    null);
        }

        if (!poSeResponse.wasChannelPreviouslyOpen()) {
            throw new KeypleCalypsoSecureSessionException("The logical channel was not open",
                    KeypleCalypsoSecureSessionException.Type.PO, poSeRequest.getApduRequests(),
                    null);
        }

        /* Retrieve and check the ApduResponses */
        List<ApduResponse> poApduResponseList = poSeResponse.getApduResponses();

        /* Do some basic checks */
        if (poApduRequestList.size() != poApduResponseList.size()) {
            throw new KeypleCalypsoSecureSessionException("Inconsistent requests and responses",
                    KeypleCalypsoSecureSessionException.Type.PO, poApduRequestList,
                    poApduResponseList);
        }

        /* Track Read Records for later use to build anticipated responses. */
        AnticipatedResponseBuilder.storeCommandResponse(poBuilderParsers, poApduRequestList,
                poApduResponseList, 0, poBuilderParsers.size() - 1);

        /*
         * Add all commands data to the digest computation if this method is called within a Secure
         * Session.
         */
        if (sessionState == SessionState.SESSION_OPEN) {
            for (int i = 0; i < poApduRequestList.size(); i++) { // The loop starts after the Open
                /*
                 * Add requests and responses to the digest processor
                 */
                samCommandsProcessor.pushPoExchangeData(poApduRequestList.get(i),
                        poApduResponseList.get(i));
            }
        }

        /* Specific processing if the request has been split */
        while (splitCommandIndex >= 0) {
            /* Execute a loop, since the request can be split several times. */
            /* Determine the command that made the request split */
            PoBuilderParser.SplitCommandInfo splitCommandInfo =
                    poBuilderParsers.get(splitCommandIndex).getSplitCommandInfo();
            switch (splitCommandInfo) {
                case VERIFY_PIN:
                    logger.debug("VERIFY PIN split  found!");
                    /*
                     * we expect here that the last received response is the answer to a Get
                     * Challenge command
                     */
                    byte[] poChallenge =
                            poApduResponseList.get(poApduResponseList.size() - 1).getDataOut();
                    /* Remove this PoTransaction internal response from the received list */
                    poApduResponseList.remove(poApduResponseList.size() - 1);
                    /* Retrieve the Verify Pin partially built command */
                    VerifyPinCmdBuild verifyPinCmdBuild = (VerifyPinCmdBuild) poBuilderParsers
                            .get(splitCommandIndex + 1).getCommandBuilder();
                    /* Get the encrypted PIN with the help of the SAM */
                    byte[] pinCipheredData = samCommandsProcessor.getCipheredPinData(poChallenge,
                            verifyPinCmdBuild.getPin(), null);
                    /* Complete the Verify Pin command builder */
                    verifyPinCmdBuild.setCipheredPinData(pinCipheredData);
                    /* Remove the Get Challenge command from the builder list (internal command) */
                    poBuilderParsers.remove(splitCommandIndex);
                    /* Clear the request list to prepare the following transmission */
                    poApduRequestList.clear();
                    /* Get the next ApduRequest list and keep the possible split index */
                    int newSplitCommandIndex =
                            buildApduRequests(poBuilderParsers, poApduRequestList);
                    /* Create a SeRequest from the ApduRequest list */
                    poSeRequest = new SeRequest(poApduRequestList);
                    /* Transmit the commands to the PO and get the responses */
                    SeResponse poSeResponseVP = poReader.transmit(poSeRequest);
                    List<ApduResponse> poApduResponseListVP = poSeResponseVP.getApduResponses();
                    /* Add requests and responses to the digest processor */
                    for (int i = 0; i < poApduRequestList.size(); i++) {
                        samCommandsProcessor.pushPoExchangeData(poApduRequestList.get(i),
                                poApduResponseListVP.get(i));
                    }
                    /* Track Read Record commands for later use to build anticipated responses. */
                    AnticipatedResponseBuilder.storeCommandResponse(poBuilderParsers,
                            poApduRequestList, poApduResponseListVP, splitCommandIndex,
                            splitCommandIndex + poApduResponseListVP.size() - 1);
                    /* Append response to the output response list */
                    poApduResponseList.addAll(poApduResponseListVP);
                    /* update the splitCommandIndex and loop */
                    splitCommandIndex = newSplitCommandIndex;
                    break;
                case NOT_SET:
                default:
                    throw new IllegalStateException("Unexpected SeRequest split");
            }
        }

        return new SeResponse(true, true, poSeResponse.getSelectionStatus(), poApduResponseList);
    }

    /**
     * Close the Secure Session.
     * <ul>
     * <li>The SAM cache is completed with the Digest Update commands related to the new PO commands
     * to be sent and their anticipated responses. A Digest Close command is also added to the SAM
     * command cache.</li>
     * <li>On the SAM session reader side, a SeRequest is transmitted with SAM commands from the
     * command cache. The SAM command cache is emptied.</li>
     * <li>The SAM certificate is retrieved from the Digest Close response. The terminal signature
     * is identified.</li>
     * <li>Then, on the PO reader, a SeRequest is transmitted with the provided channelControl, and
     * apduRequests including the new PO commands to send in the session, a Close Session command
     * (defined with the SAM certificate), and optionally a ratificationCommand.
     * <ul>
     * <li>The management of ratification is conditioned by the mode of communication.
     * <ul>
     * <li>If the communication mode is CONTACTLESS, a specific ratification command is sent after
     * the Close Session command. No ratification is requested in the Close Session command.</li>
     * <li>If the communication mode is CONTACTS, no ratification command is sent after the Close
     * Session command. Ratification is requested in the Close Session command.</li>
     * </ul>
     * </li>
     * <li>Otherwise, the PO Close Secure Session command is defined to directly set the PO as
     * ratified.</li>
     * </ul>
     * </li>
     * <li>The PO responses of the poModificationCommands are compared with the
     * poAnticipatedResponses. The PO signature is identified from the PO Close Session
     * response.</li>
     * <li>The PO certificate is recovered from the Close Session response. The card signature is
     * identified.</li>
     * <li>Finally, on the SAM session reader, a Digest Authenticate is automatically operated in
     * order to verify the PO signature.</li>
     * <li>Returns the corresponding PO SeResponse.</li>
     * </ul>
     *
     * The method is marked as deprecated because the advanced variant defined below must be used at
     * the application level.
     * 
     * @param poModificationCommands a list of commands that can modify the PO memory content
     * @param poAnticipatedResponses a list of anticipated PO responses to the modification commands
     * @param transmissionMode the communication mode. If the communication mode is CONTACTLESS, a
     *        ratification command will be generated and sent to the PO after the Close Session
     *        command; the ratification will not be requested in the Close Session command. On the
     *        contrary, if the communication mode is CONTACTS, no ratification command will be sent
     *        to the PO and ratification will be requested in the Close Session command
     * @param channelControl indicates if the SE channel of the PO reader must be closed after the
     *        last command
     * @return SeResponse close session response
     * @throws KeypleReaderException the IO reader exception This method is deprecated.
     *         <ul>
     *         <li>The argument of the ratification command is replaced by an indication of the PO
     *         communication mode.</li>
     *         </ul>
     */
    private SeResponse processAtomicClosing(List<PoBuilderParser> poModificationCommands,
            List<ApduResponse> poAnticipatedResponses, TransmissionMode transmissionMode,
            ChannelControl channelControl) throws KeypleReaderException {

        if (sessionState != SessionState.SESSION_OPEN) {
            throw new IllegalStateException("Bad session state. Current: " + sessionState.toString()
                    + ", expected: " + SessionState.SESSION_OPEN.toString());
        }

        /* Get PO ApduRequest List from PoSendableInSession List - for the first PO exchange */
        List<ApduRequest> poApduRequestList = new ArrayList<ApduRequest>();
        this.buildApduRequests(poModificationCommands, poApduRequestList);

        /* Compute "anticipated" Digest Update (for optional poModificationCommands) */
        if ((poModificationCommands != null) && !poApduRequestList.isEmpty()) {
            if (poApduRequestList.size() == poAnticipatedResponses.size()) {
                /*
                 * Add all commands data to the digest computation: commands and anticipated
                 * responses.
                 */
                for (int i = 0; i < poApduRequestList.size(); i++) {
                    /*
                     * Add requests and responses to the digest processor
                     */
                    samCommandsProcessor.pushPoExchangeData(poApduRequestList.get(i),
                            poAnticipatedResponses.get(i));
                }
            } else {
                throw new KeypleCalypsoSecureSessionException(
                        "Inconsistent requests and anticipated responses",
                        KeypleCalypsoSecureSessionException.Type.PO, poApduRequestList,
                        poAnticipatedResponses);
            }
        }

        /* All SAM digest operations will now run at once. */
        /* Get Terminal Signature from the latest response */
        byte[] sessionTerminalSignature = samCommandsProcessor.getTerminalSignature();

        if (logger.isDebugEnabled()) {
            logger.debug("processAtomicClosing => SIGNATURE = {}",
                    ByteArrayUtil.toHex(sessionTerminalSignature));
        }

        PoCustomReadCommandBuilder ratificationCommand;
        boolean ratificationAsked;

        if (transmissionMode == TransmissionMode.CONTACTLESS) {
            if (PoRevision.REV2_4.equals(calypsoPo.getRevision())) {
                ratificationCommand = new PoCustomReadCommandBuilder("Ratification command",
                        new ApduRequest(ratificationCmdApduLegacy, false));
            } else {
                ratificationCommand = new PoCustomReadCommandBuilder("Ratification command",
                        new ApduRequest(ratificationCmdApdu, false));
            }
            /*
             * Ratification is done by the ratification command above so is not requested in the
             * Close Session command
             */
            ratificationAsked = false;
        } else {
            /* Ratification is requested in the Close Session command in contacts mode */
            ratificationAsked = true;
            ratificationCommand = null;
        }

        /* Build the PO Close Session command. The last one for this session */
        CloseSessionCmdBuild closeCommand = new CloseSessionCmdBuild(calypsoPo.getPoClass(),
                ratificationAsked, sessionTerminalSignature);

        poApduRequestList.add(closeCommand.getApduRequest());

        /* Keep the position of the Close Session command in request list */
        int closeCommandIndex = poApduRequestList.size() - 1;

        /*
         * Add the PO Ratification command if any
         */
        if (ratificationCommand != null) {
            poApduRequestList.add(ratificationCommand.getApduRequest());
        }

        /*
         * Transfer PO commands
         */
        SeRequest poSeRequest = new SeRequest(poApduRequestList);

        logger.trace("processAtomicClosing => POSEREQUEST = {}", poSeRequest);

        SeResponse poSeResponse;
        try {
            poSeResponse = poReader.transmit(poSeRequest, channelControl);
        } catch (KeypleReaderException ex) {
            poSeResponse = ex.getSeResponse();
            /*
             * The current exception may have been caused by a communication issue with the PO
             * during the ratification command.
             *
             * In this case, we do not stop the process and consider the Secure Session close. We'll
             * check the signature.
             *
             * We should have one response less than requests.
             */
            if (ratificationAsked || poSeResponse == null
                    || poSeResponse.getApduResponses().size() != poApduRequestList.size() - 1) {
                /* Add current PO SeResponse to exception */
                ex.setSeResponse(poSeResponse);
                throw new KeypleReaderException("PO Reader Exception while closing Secure Session",
                        ex);
            }
        }

        if (poSeResponse == null) {
            throw new KeypleCalypsoSecureSessionException("Null response received",
                    KeypleCalypsoSecureSessionException.Type.PO, poSeRequest.getApduRequests(),
                    null);
        }

        if (!poSeResponse.wasChannelPreviouslyOpen()) {
            throw new KeypleCalypsoSecureSessionException("The logical channel was not open",
                    KeypleCalypsoSecureSessionException.Type.PO, poSeRequest.getApduRequests(),
                    null);
        }

        logger.trace("processAtomicClosing => POSERESPONSE = {}", poSeResponse);

        List<ApduResponse> poApduResponseList = poSeResponse.getApduResponses();

        // TODO add support of poRevision parameter to CloseSessionRespPars for REV2.4 PO CLAss byte
        // before last if ratification, otherwise last one
        CloseSessionRespPars poCloseSessionPars =
                new CloseSessionRespPars(poApduResponseList.get(closeCommandIndex));
        if (!poCloseSessionPars.isSuccessful()) {
            throw new KeypleCalypsoSecureSessionException("Didn't get a signature",
                    KeypleCalypsoSecureSessionException.Type.PO, poApduRequestList,
                    poApduResponseList);
        }

        transactionResult =
                samCommandsProcessor.authenticatePoSignature(poCloseSessionPars.getSignatureLo());

        if (transactionResult) {
            if (poCommandsManager.isSvOperationPending()) {
                // we check the SV status only if the session has been successfully closed
                transactionResult = samCommandsProcessor
                        .getSvCheckStatus(poCloseSessionPars.getPostponedData());
                if (!transactionResult) {
                    logger.error(
                            "checkPoSignature: the mutual authentication was successful but the SV operation failed.");
                }
            } else {
                logger.debug("checkPoSignature: mutual authentication successful.");
            }
        } else {
            logger.error("checkPoSignature: mutual authentication failure.");
        }

        sessionState = SessionState.SESSION_CLOSED;

        /* Remove ratification response if any */
        if (!ratificationAsked) {
            poApduResponseList.remove(poApduResponseList.size() - 1);
        }
        /* Remove Close Secure Session response and create a new SeResponse */
        poApduResponseList.remove(poApduResponseList.size() - 1);

        return new SeResponse(true, true, poSeResponse.getSelectionStatus(), poApduResponseList);
    }

    /**
     * Advanced variant of processAtomicClosing in which the list of expected responses is
     * determined from previous reading operations.
     *
     * @param poBuilderParsers a list of commands that can modify the PO memory content
     * @param transmissionMode the communication mode. If the communication mode is CONTACTLESS, a
     *        ratification command will be generated and sent to the PO after the Close Session
     *        command; the ratification will not be requested in the Close Session command. On the
     *        contrary, if the communication mode is CONTACTS, no ratification command will be sent
     *        to the PO and ratification will be requested in the Close Session command
     * @param channelControl indicates if the SE channel of the PO reader must be closed after the
     *        last command
     * @return SeResponse close session response
     * @throws KeypleReaderException the IO reader exception This method is deprecated.
     *         <ul>
     *         <li>The argument of the ratification command is replaced by an indication of the PO
     *         communication mode.</li>
     *         </ul>
     */
    private SeResponse processAtomicClosing(List<PoBuilderParser> poBuilderParsers,
            TransmissionMode transmissionMode, ChannelControl channelControl)
            throws KeypleReaderException {
        List<ApduResponse> poAnticipatedResponses =
                AnticipatedResponseBuilder.getResponses(poBuilderParsers);
        return processAtomicClosing(poBuilderParsers, poAnticipatedResponses, transmissionMode,
                channelControl);
    }

    /**
     * Get the Secure Session Status.
     * <ul>
     * <li>To check the result of a closed secure session, returns true if the SAM Digest
     * Authenticate is successful.</li>
     * </ul>
     *
     * @return the {@link PoTransaction}.transactionResult
     */
    public boolean isSuccessful() {
        // TODO add checks
        // if (sessionState != SessionState.SESSION_CLOSED) {
        // throw new IllegalStateException(
        // "Session is not closed, state:" + sessionState.toString() + ", expected: "
        // + SessionState.SESSION_OPEN.toString());
        // }

        return transactionResult;
    }

    /**
     * Get the ratification status obtained at Session Opening
     * 
     * @return true or false
     * @throws IllegalStateException if no session has been initiated
     */
    public boolean wasRatified() {
        if (sessionState == SessionState.SESSION_UNINITIALIZED) {
            throw new IllegalStateException("No active session.");
        }
        return wasRatified;
    }

    /**
     * Get the data read at Session Opening
     * 
     * @return a byte array containing the data
     * @throws IllegalStateException if no session has been initiated
     */
    public byte[] getOpenRecordDataRead() {
        if (sessionState == SessionState.SESSION_UNINITIALIZED) {
            throw new IllegalStateException("No active session.");
        }
        return openRecordDataRead;
    }

    /**
     * The PO Transaction Access Level: personalization, loading or debiting.
     */
    public enum SessionAccessLevel {
        /** Session Access Level used for personalization purposes. */
        SESSION_LVL_PERSO("perso", (byte) 0x01),
        /** Session Access Level used for reloading purposes. */
        SESSION_LVL_LOAD("load", (byte) 0x02),
        /** Session Access Level used for validating and debiting purposes. */
        SESSION_LVL_DEBIT("debit", (byte) 0x03);

        private final String name;
        private final byte sessionKey;

        SessionAccessLevel(String name, byte sessionKey) {
            this.name = name;
            this.sessionKey = sessionKey;
        }

        public String getName() {
            return name;
        }

        public byte getSessionKey() {
            return sessionKey;
        }
    }

    /**
     * The modification mode indicates whether the secure session can be closed and reopened to
     * manage the limitation of the PO buffer memory.
     */
    public enum ModificationMode {
        /**
         * The secure session is atomic. The consistency of the content of the resulting PO memory
         * is guaranteed.
         */
        ATOMIC,
        /**
         * Several secure sessions can be chained (to manage the writing of large amounts of data).
         * The resulting content of the PO's memory can be inconsistent if the PO is removed during
         * the process.
         */
        MULTIPLE
    }

    /**
     * The PO Transaction State defined with the elements: ‘IOError’, ‘SEInserted’ and ‘SERemoval’.
     */
    private enum SessionState {
        /** Initial state of a PO transaction. The PO must have been previously selected. */
        SESSION_UNINITIALIZED,
        /** The secure session is active. */
        SESSION_OPEN,
        /** The secure session is closed. */
        SESSION_CLOSED
    }

    /**
     * The class handles the anticipated response computation.
     */
    private static class AnticipatedResponseBuilder {
        /**
         * A nested class to associate a request with a response
         */
        private static class CommandResponse {
            private final ApduRequest apduRequest;
            private final ApduResponse apduResponse;

            CommandResponse(ApduRequest apduRequest, ApduResponse apduResponse) {
                this.apduRequest = apduRequest;
                this.apduResponse = apduResponse;
            }

            public ApduRequest getApduRequest() {
                return apduRequest;
            }

            public ApduResponse getApduResponse() {
                return apduResponse;
            }
        }

        /**
         * A Map of SFI and Commands/Responses
         */
        private static Map<Byte, CommandResponse> sfiCommandResponseHashMap =
                new HashMap<Byte, CommandResponse>();

        /**
         * Store all Read Record exchanges in a Map whose key is the SFI.
         * 
         * @param poBuilderParsers the list of commands sent to the PO
         * @param apduRequests the sent apduRequests
         * @param apduResponses the received apduResponses
         * @param first the item of the first command to consider.
         * @param last the item of the last command to consider.
         */
        static void storeCommandResponse(List<PoBuilderParser> poBuilderParsers,
                List<ApduRequest> apduRequests, List<ApduResponse> apduResponses, int first,
                int last) {
            if (poBuilderParsers != null) {
                /*
                 * Store Read Records' requests and responses for later use to build anticipated
                 * responses.
                 */
                Iterator<ApduRequest> apduRequestIterator = apduRequests.iterator();
                Iterator<ApduResponse> apduResponseIterator = apduResponses.iterator();
                /* Iterate over the poCommandsInsideSession list */
                for (int i = 0; i < last; i++) {
                    if (i < first) {
                        /* ex. case of processAtomicOpening */
                        continue;
                    }
                    PoBuilderParser poCommand = poBuilderParsers.get(i);
                    if ((poCommand).getCommandBuilder() instanceof ReadRecordsCmdBuild) {
                        ApduRequest apduRequest = apduRequestIterator.next();
                        // TODO improve this ugly code
                        byte sfi = (byte) ((apduRequest.getBytes()[OFFSET_P2] >> 3) & 0x1F);
                        sfiCommandResponseHashMap.put(sfi,
                                new CommandResponse(apduRequest, apduResponseIterator.next()));
                    } else {
                        if (apduRequestIterator.hasNext()) {
                            apduRequestIterator.next();
                            apduResponseIterator.next();
                        } else {
                            return;
                        }
                    }
                }
            }
        }

        /**
         * Establish the anticipated responses to commands provided in poModificationCommands.
         * <p>
         * Append Record and Update Record commands return 9000
         * <p>
         * Increase and Decrease return NNNNNN9000 where NNNNNNN is the new counter value.
         * <p>
         * NNNNNN is determine with the current value of the counter (extracted from the Read Record
         * responses previously collected) and the value to add or subtract provided in the command.
         * <p>
         * The SFI field is used to determine which data should be used to extract the needed
         * information.
         *
         * @param poBuilderParsers the modification command list
         * @return the anticipated responses.
         * @throws KeypleCalypsoSecureSessionException if an response can't be determined.
         */
        private static List<ApduResponse> getResponses(List<PoBuilderParser> poBuilderParsers)
                throws KeypleCalypsoSecureSessionException {
            List<ApduResponse> apduResponses = new ArrayList<ApduResponse>();
            if (poBuilderParsers != null) {
                for (PoBuilderParser poBuilderParser : poBuilderParsers) {
                    if (poBuilderParser.getCommandBuilder() instanceof DecreaseCmdBuild
                            || poBuilderParser.getCommandBuilder() instanceof IncreaseCmdBuild) {
                        /* response = NNNNNN9000 */
                        byte[] modCounterApduRequest =
                                (poBuilderParser.getCommandBuilder()).getApduRequest().getBytes();
                        /* Retrieve SFI from the current Decrease command */
                        byte sfi = (byte) ((modCounterApduRequest[OFFSET_P2] >> 3) & 0x1F);
                        /*
                         * Look for the counter value in the stored records. Only the first
                         * occurrence of the SFI is taken into account. We assume here that the
                         * record number is always 1.
                         */
                        CommandResponse commandResponse = sfiCommandResponseHashMap.get(sfi);
                        if (commandResponse != null) {
                            byte counterNumber = modCounterApduRequest[OFFSET_P1];
                            /*
                             * The record containing the counters is structured as follow:
                             * AAAAAAABBBBBBCCCCCC...XXXXXX each counter being a 3-byte unsigned
                             * number. Convert the 3-byte block indexed by the counter number to an
                             * int.
                             */
                            int currentCounterValue = ByteArrayUtil.threeBytesToInt(
                                    commandResponse.getApduResponse().getBytes(),
                                    (counterNumber - 1) * 3);
                            /* Extract the add or subtract value from the modification request */
                            int addSubtractValue = ByteArrayUtil
                                    .threeBytesToInt(modCounterApduRequest, OFFSET_DATA);
                            /* Build the response */
                            byte[] response = new byte[5];
                            int newCounterValue;
                            if (poBuilderParser.getCommandBuilder() instanceof DecreaseCmdBuild) {
                                newCounterValue = currentCounterValue - addSubtractValue;
                            } else {
                                newCounterValue = currentCounterValue + addSubtractValue;
                            }
                            response[0] = (byte) ((newCounterValue & 0x00FF0000) >> 16);
                            response[1] = (byte) ((newCounterValue & 0x0000FF00) >> 8);
                            response[2] = (byte) ((newCounterValue & 0x000000FF) >> 0);
                            response[3] = (byte) 0x90;
                            response[4] = (byte) 0x00;
                            apduResponses.add(new ApduResponse(response, null));
                            if (logger.isDebugEnabled()) {
                                logger.debug(
                                        "Anticipated response. COMMAND = {}, SFI = {}, COUNTERVALUE = {}, DECREMENT = {}, NEWVALUE = {} ",
                                        (poBuilderParser
                                                .getCommandBuilder() instanceof DecreaseCmdBuild)
                                                        ? "Decrease"
                                                        : "Increase",
                                        sfi, currentCounterValue, addSubtractValue,
                                        newCounterValue);
                            }
                        } else {
                            throw new KeypleCalypsoSecureSessionException(
                                    "Anticipated response. COMMAND = " + ((poBuilderParser
                                            .getCommandBuilder() instanceof DecreaseCmdBuild)
                                                    ? "Decrease"
                                                    : "Increase")
                                            + ". Unable to determine anticipated counter value. SFI = "
                                            + sfi,
                                    poBuilderParser.getCommandBuilder().getApduRequest(), null);
                        }
                    } else if (poBuilderParser.getCommandBuilder() instanceof SvReloadCmdBuild
                            || poBuilderParser.getCommandBuilder() instanceof SvDebitCmdBuild
                            || poBuilderParser.getCommandBuilder() instanceof SvUndebitCmdBuild) {
                        /* Append/Update/Write Record: response = 9000 */
                        apduResponses.add(new ApduResponse(ByteArrayUtil.fromHex("6200"), null));
                    } else {
                        /* Append/Update/Write Record: response = 9000 */
                        apduResponses.add(new ApduResponse(ByteArrayUtil.fromHex("9000"), null));
                    }
                }
            }
            return apduResponses;
        }
    }

    /**
     * Open a Secure Session.
     * <ul>
     * <li>The PO must have been previously selected, so a logical channel with the PO application
     * must be already active.</li>
     * <li>The PO serial &amp; revision are identified from FCI data.</li>
     * <li>A first request is sent to the SAM session reader.
     * <ul>
     * <li>In case not logical channel is active with the SAM, a channel is open.</li>
     * <li>Then a Select Diversifier (with the PO serial) &amp; a Get Challenge are automatically
     * operated. The SAM challenge is recovered.</li>
     * </ul>
     * </li>
     * <li>The PO Open Session command is built according to the PO revision, the SAM challenge, the
     * keyIndex, and openingSfiToSelect / openingRecordNumberToRead.</li>
     * <li>Next the PO reader is requested:
     * <ul>
     * <li>for the currently selected PO, with channelControl set to KEEP_OPEN,</li>
     * <li>and some PO Apdu Requests including at least the Open Session command and all prepared PO
     * command to operate inside the session.</li>
     * </ul>
     * </li>
     * <li>The session PO keyset reference is identified from the PO Open Session response, the PO
     * challenge is recovered too.</li>
     * <li>According to the PO responses of Open Session and the PO commands sent inside the
     * session, a "cache" of SAM commands is filled with the corresponding Digest Init &amp; Digest
     * Update commands.</li>
     * <li>All parsers keept by the prepare command methods are updated with the Apdu responses from
     * the PO and made available with the getCommandParser method.</li>
     * </ul>
     *
     * @param modificationMode the modification mode: ATOMIC or MULTIPLE (see
     *        {@link ModificationMode})
     * @param accessLevel access level of the session (personalization, load or debit).
     * @param openingSfiToSelect SFI of the file to select (0 means no file to select)
     * @param openingRecordNumberToRead number of the record to read
     * @return true if all commands are successful
     * @throws KeypleReaderException the IO reader exception
     */
    public boolean processOpening(ModificationMode modificationMode, SessionAccessLevel accessLevel,
            byte openingSfiToSelect, byte openingRecordNumberToRead) throws KeypleReaderException {
        currentModificationMode = modificationMode;
        currentAccessLevel = accessLevel;
        byte localOpeningRecordNumberToRead = openingRecordNumberToRead;
        boolean poProcessSuccess = true;

        /* informs the command manager that a secure session has been opened. */
        poCommandsManager.setSecureSessionIsOpen(true);

        /* create a sublist of PoBuilderParser to be sent atomically */
        List<PoBuilderParser> poAtomicCommandList = new ArrayList<PoBuilderParser>();
        for (PoBuilderParser poCommandElement : poCommandsManager.getPoBuilderParserList()) {
            if (!(poCommandElement.getCommandBuilder() instanceof PoModificationCommand)) {
                /* This command does not affect the PO modifications buffer */
                poAtomicCommandList.add(poCommandElement);
            } else {
                /* This command affects the PO modifications buffer */
                if (willOverflowBuffer(
                        (PoModificationCommand) poCommandElement.getCommandBuilder())) {
                    if (currentModificationMode == ModificationMode.ATOMIC) {
                        throw new IllegalStateException(
                                "ATOMIC mode error! This command would overflow the PO modifications buffer: "
                                        + poCommandElement.getCommandBuilder().toString());
                    }
                    SeResponse seResponseOpening =
                            processAtomicOpening(currentAccessLevel, openingSfiToSelect,
                                    localOpeningRecordNumberToRead, poAtomicCommandList);

                    /*
                     * inhibit record reading for next round, keep file selection (TODO check this)
                     */
                    localOpeningRecordNumberToRead = (byte) 0x00;

                    if (!createResponseParsers(seResponseOpening,
                            poCommandsManager.getPoBuilderParserList())) {
                        poProcessSuccess = false;
                    }
                    /*
                     * Closes the session, resets the modifications buffer counters for the next
                     * round (set the contact mode to avoid the transmission of the ratification)
                     */
                    processAtomicClosing(null, TransmissionMode.CONTACTS, ChannelControl.KEEP_OPEN);
                    resetModificationsBufferCounter();
                    /*
                     * Clear the list and add the command that did not fit in the PO modifications
                     * buffer. We also update the usage counter without checking the result.
                     */
                    poAtomicCommandList.clear();
                    poAtomicCommandList.add(poCommandElement);
                    /*
                     * just update modifications buffer usage counter, ignore result (always false)
                     */
                    willOverflowBuffer(
                            (PoModificationCommand) poCommandElement.getCommandBuilder());
                } else {
                    /*
                     * The command fits in the PO modifications buffer, just add it to the list
                     */
                    poAtomicCommandList.add(poCommandElement);
                }
            }
        }

        SeResponse seResponseOpening = processAtomicOpening(currentAccessLevel, openingSfiToSelect,
                localOpeningRecordNumberToRead, poAtomicCommandList);

        if (!createResponseParsers(seResponseOpening, poAtomicCommandList)) {
            poProcessSuccess = false;
        }

        /* sets the flag indicating that the commands have been executed */
        poCommandsManager.notifyCommandsProcessed();

        return poProcessSuccess;
    }

    /**
     * Process all prepared PO commands (outside a Secure Session).
     * <ul>
     * <li>On the PO reader, generates a SeRequest with channelControl set to the provided value and
     * ApduRequests containing the PO commands.</li>
     * <li>All parsers keept by the prepare command methods are updated with the Apdu responses from
     * the PO and made available with the getCommandParser method.</li>
     * </ul>
     *
     * @param channelControl indicates if the SE channel of the PO reader must be closed after the
     *        last command
     * @return true if all commands are successful
     *
     * @throws KeypleReaderException IO Reader exception
     */
    public boolean processPoCommands(ChannelControl channelControl) throws KeypleReaderException {

        /** This method should be called only if no session was previously open */
        if (sessionState == SessionState.SESSION_OPEN) {
            throw new IllegalStateException("A session is open");
        }

        boolean poProcessSuccess = true;

        /* PO commands sent outside a Secure Session. No modifications buffer limitation. */
        SeResponse seResponsePoCommands =
                processAtomicPoCommands(poCommandsManager.getPoBuilderParserList(), channelControl);

        if (!createResponseParsers(seResponsePoCommands,
                poCommandsManager.getPoBuilderParserList())) {
            poProcessSuccess = false;
        }

        if (poCommandsManager.isSvOperationPending()) {
            if (!samCommandsProcessor.getSvCheckStatus(poCommandsManager
                    .getSvOperationResponseParser().getApduResponse().getDataOut())) {
                throw new KeypleCalypsoSvSecurityException("Stored Value check failed!");
            }
        }

        /* sets the flag indicating that the commands have been executed */
        poCommandsManager.notifyCommandsProcessed();

        return poProcessSuccess;
    }

    /**
     * Process all prepared PO commands in a Secure Session.
     * <ul>
     * <li>On the PO reader, generates a SeRequest with channelControl set to KEEP_OPEN, and
     * ApduRequests containing the PO commands.</li>
     * <li>In case the secure session is active, the "cache" of SAM commands is completed with the
     * corresponding Digest Update commands.</li>
     * <li>All parsers keept by the prepare command methods are updated with the Apdu responses from
     * the PO and made available with the getCommandParser method.</li>
     * </ul>
     *
     * @return true if all commands are successful
     *
     * @throws KeypleReaderException IO Reader exception
     */
    public boolean processPoCommandsInSession() throws KeypleReaderException {

        /** This method should be called only if a session was previously open */
        if (sessionState != SessionState.SESSION_OPEN) {
            throw new IllegalStateException("No open session");
        }

        boolean poProcessSuccess = true;

        /* A session is open, we have to care about the PO modifications buffer */
        List<PoBuilderParser> poAtomicBuilderParserList = new ArrayList<PoBuilderParser>();

        for (PoBuilderParser poBuilderParser : poCommandsManager.getPoBuilderParserList()) {
            if (!(poBuilderParser.getCommandBuilder() instanceof PoModificationCommand)) {
                /* This command does not affect the PO modifications buffer */
                poAtomicBuilderParserList.add(poBuilderParser);
            } else {
                /* This command affects the PO modifications buffer */
                if (willOverflowBuffer(
                        ((PoModificationCommand) poBuilderParser.getCommandBuilder()))) {
                    if (currentModificationMode == ModificationMode.ATOMIC) {
                        throw new IllegalStateException(
                                "ATOMIC mode error! This command would overflow the PO modifications buffer: "
                                        + poBuilderParser.getCommandBuilder().toString());
                    }
                    /*
                     * The current command would overflow the modifications buffer in the PO. We
                     * send the current commands and update the parsers. The parsers Iterator is
                     * kept all along the process.
                     */
                    SeResponse seResponsePoCommands = processAtomicPoCommands(
                            poAtomicBuilderParserList, ChannelControl.KEEP_OPEN);
                    if (!createResponseParsers(seResponsePoCommands, poAtomicBuilderParserList)) {
                        poProcessSuccess = false;
                    }
                    /*
                     * Close the session and reset the modifications buffer counters for the next
                     * round (set the contact mode to avoid the transmission of the ratification)
                     */
                    processAtomicClosing(null, TransmissionMode.CONTACTS, ChannelControl.KEEP_OPEN);
                    resetModificationsBufferCounter();
                    /* We reopen a new session for the remaining commands to be sent */
                    processAtomicOpening(currentAccessLevel, (byte) 0x00, (byte) 0x00, null);
                    /*
                     * Clear the list and add the command that did not fit in the PO modifications
                     * buffer. We also update the usage counter without checking the result.
                     */
                    poAtomicBuilderParserList.clear();
                    poAtomicBuilderParserList.add(poBuilderParser);
                    /*
                     * just update modifications buffer usage counter, ignore result (always false)
                     */
                    willOverflowBuffer((PoModificationCommand) poBuilderParser.getCommandBuilder());
                } else {
                    /*
                     * The command fits in the PO modifications buffer, just add it to the list
                     */
                    poAtomicBuilderParserList.add(poBuilderParser);
                }
            }
        }

        if (!poAtomicBuilderParserList.isEmpty()) {
            SeResponse seResponsePoCommands =
                    processAtomicPoCommands(poAtomicBuilderParserList, ChannelControl.KEEP_OPEN);
            if (!createResponseParsers(seResponsePoCommands, poAtomicBuilderParserList)) {
                poProcessSuccess = false;
            }
        }

        /* sets the flag indicating that the commands have been executed */
        poCommandsManager.notifyCommandsProcessed();

        return poProcessSuccess;
    }

    /**
     * Sends the currently prepared commands list (may be empty) and closes the Secure Session.
     * <ul>
     * <li>The ratification is handled according to the communication mode.</li>
     * <li>The logical channel can be left open or closed.</li>
     * <li>All parsers keept by the prepare command methods are updated with the Apdu responses from
     * the PO and made available with the getCommandParser method.</li>
     * </ul>
     *
     * <p>
     * The communication mode is retrieved from CalypsoPO to manage the ratification process. If the
     * communication mode is CONTACTLESS, a ratification command will be generated and sent to the
     * PO after the Close Session command; the ratification will not be requested in the Close
     * Session command. On the contrary, if the communication mode is CONTACTS, no ratification
     * command will be sent to the PO and ratification will be requested in the Close Session
     * command
     * 
     * @param channelControl indicates if the SE channel of the PO reader must be closed after the
     *        last command
     * @return true if all commands are successful
     * @throws KeypleReaderException the IO reader exception This method is deprecated.
     *         <ul>
     *         <li>The argument of the ratification command is replaced by an indication of the PO
     *         communication mode.</li>
     *         </ul>
     */
    public boolean processClosing(ChannelControl channelControl) throws KeypleReaderException {
        boolean poProcessSuccess = true;
        boolean atLeastOneReadCommand = false;
        boolean sessionPreviouslyClosed = false;

        List<PoBuilderParser> poAtomicBuilderParserList = new ArrayList<PoBuilderParser>();
        SeResponse seResponseClosing;
        for (PoBuilderParser poBuilderParser : poCommandsManager.getPoBuilderParserList()) {
            if (!(poBuilderParser instanceof PoModificationCommand)) {
                /*
                 * This command does not affect the PO modifications buffer. We will call
                 * processPoCommands first
                 */
                poAtomicBuilderParserList.add(poBuilderParser);
                atLeastOneReadCommand = true;
            } else {
                /* This command affects the PO modifications buffer */
                if (willOverflowBuffer(
                        (PoModificationCommand) poBuilderParser.getCommandBuilder())) {
                    if (currentModificationMode == ModificationMode.ATOMIC) {
                        throw new IllegalStateException(
                                "ATOMIC mode error! This command would overflow the PO modifications buffer: "
                                        + poBuilderParser.getCommandBuilder().toString());
                    }
                    /*
                     * Reopen a session with the same access level if it was previously closed in
                     * this current processClosing
                     */
                    if (sessionPreviouslyClosed) {
                        processAtomicOpening(currentAccessLevel, (byte) 0x00, (byte) 0x00, null);
                    }

                    /*
                     * If at least one non-modifying was prepared, we use processAtomicPoCommands
                     * instead of processAtomicClosing to send the list
                     */
                    if (atLeastOneReadCommand) {
                        List<PoBuilderParser> poBuilderParsers = new ArrayList<PoBuilderParser>();
                        poBuilderParsers.addAll(poAtomicBuilderParserList);
                        seResponseClosing =
                                processAtomicPoCommands(poBuilderParsers, ChannelControl.KEEP_OPEN);
                        atLeastOneReadCommand = false;
                    } else {
                        /* All commands in the list are 'modifying' */
                        seResponseClosing = processAtomicClosing(poAtomicBuilderParserList,
                                TransmissionMode.CONTACTS, ChannelControl.KEEP_OPEN);
                        resetModificationsBufferCounter();
                        sessionPreviouslyClosed = true;
                    }

                    if (!createResponseParsers(seResponseClosing, poAtomicBuilderParserList)) {
                        poProcessSuccess = false;
                    }
                    /*
                     * Clear the list and add the command that did not fit in the PO modifications
                     * buffer. We also update the usage counter without checking the result.
                     */
                    poAtomicBuilderParserList.clear();
                    poAtomicBuilderParserList.add(poBuilderParser);
                    /*
                     * just update modifications buffer usage counter, ignore result (always false)
                     */
                    willOverflowBuffer((PoModificationCommand) poBuilderParser.getCommandBuilder());
                } else {
                    /*
                     * The command fits in the PO modifications buffer, just add it to the list
                     */
                    poAtomicBuilderParserList.add(poBuilderParser);
                }
            }
        }
        if (sessionPreviouslyClosed) {
            /*
             * Reopen if needed, to close the session with the requested conditions
             * (CommunicationMode and channelControl)
             */
            processAtomicOpening(currentAccessLevel, (byte) 0x00, (byte) 0x00, null);
        }

        /* Finally, close the session as requested */
        seResponseClosing = processAtomicClosing(poAtomicBuilderParserList,
                calypsoPo.getTransmissionMode(), channelControl);

        /* Update parsers */
        if (!createResponseParsers(seResponseClosing, poAtomicBuilderParserList)) {
            poProcessSuccess = false;
        }

        /* sets the flag indicating that the commands have been executed */
        poCommandsManager.notifyCommandsProcessed();

        /* informs the command manager that the secure session has been closed. */
        poCommandsManager.setSecureSessionIsOpen(false);

        return poProcessSuccess;
    }

    /**
     * Abort a Secure Session.
     * <p>
     * Send the appropriate command to the PO
     * <p>
     * Clean up internal data and status.
     * 
     * @param channelControl indicates if the SE channel of the PO reader must be closed after the
     *        abort session command
     * @return true if the abort command received a successful response from the PO
     */
    public boolean processCancel(ChannelControl channelControl) {
        /* PO ApduRequest List to hold Close Secure Session command */
        List<ApduRequest> poApduRequestList = new ArrayList<ApduRequest>();

        /* Build the PO Close Session command (in "abort" mode since no signature is provided). */
        CloseSessionCmdBuild closeCommand = new CloseSessionCmdBuild(calypsoPo.getPoClass());

        poApduRequestList.add(closeCommand.getApduRequest());

        /*
         * Transfer PO commands
         */
        SeRequest poSeRequest = new SeRequest(poApduRequestList);

        logger.trace("processCancel => POSEREQUEST = {}", poSeRequest);

        SeResponse poSeResponse;
        try {
            poSeResponse = poReader.transmit(poSeRequest, channelControl);
        } catch (KeypleReaderException ex) {
            poSeResponse = ex.getSeResponse();
        }

        logger.trace("processCancel => POSERESPONSE = {}", poSeResponse);

        /* sets the flag indicating that the commands have been executed */
        poCommandsManager.notifyCommandsProcessed();

        /*
         * session is now considered closed regardless the previous state or the result of the abort
         * session command sent to the PO.
         */
        sessionState = SessionState.SESSION_CLOSED;

        /* return the successful status of the abort session command */
        return poSeResponse.getApduResponses().get(0).isSuccessful();
    }

    /**
     * Loops on the SeResponse and create the appropriate builders
     * 
     * @param seResponse the seResponse from the PO
     * @param poBuilderParsers the list of {@link PoBuilderParser} (sublist of the global list)
     * @return false if one or more of the commands do not succeed
     */
    private boolean createResponseParsers(SeResponse seResponse,
            List<PoBuilderParser> poBuilderParsers) {
        boolean allSuccessfulCommands = true;
        Iterator<PoBuilderParser> commandIterator = poBuilderParsers.iterator();
        /* double loop to set apdu responses to corresponding parsers */
        for (ApduResponse apduResponse : seResponse.getApduResponses()) {
            if (!commandIterator.hasNext()) {
                throw new IllegalStateException("Commands list and responses list mismatch! ");
            }
            PoBuilderParser poBuilderParser = commandIterator.next();
            poBuilderParser.setResponseParser((AbstractPoResponseParser) (poBuilderParser
                    .getCommandBuilder().createResponseParser(apduResponse)));
            if (!apduResponse.isSuccessful()) {
                allSuccessfulCommands = false;
            }
        }
        return allSuccessfulCommands;
    }

    /**
     * Checks whether the requirement for the modifications buffer of the command provided in
     * argument is compatible with the current usage level of the buffer.
     * <p>
     * If it is compatible, the requirement is subtracted from the current level and the method
     * returns false. If this is not the case, the method returns true.
     * 
     * @param modificationCommand the modification command
     * @return true or false
     */
    private boolean willOverflowBuffer(PoModificationCommand modificationCommand) {
        boolean willOverflow = false;
        if (modificationsCounterIsInBytes) {
            int bufferRequirement = ((AbstractApduCommandBuilder) modificationCommand)
                    .getApduRequest().getBytes()[OFFSET_Lc] + 6;

            if (modificationsCounter - bufferRequirement > 0) {
                modificationsCounter = modificationsCounter - bufferRequirement;
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace(
                            "Modifications buffer overflow! BYTESMODE, CURRENTCOUNTER = {}, REQUIREMENT = {}",
                            modificationsCounter, bufferRequirement);
                }
                willOverflow = true;
            }
        } else {
            if (modificationsCounter > 0) {
                modificationsCounter--;
            } else {
                if (logger.isTraceEnabled()) {
                    logger.trace(
                            "Modifications buffer overflow! COMMANDSMODE, CURRENTCOUNTER = {}, REQUIREMENT = {}",
                            modificationsCounter, 1);
                }
                willOverflow = true;
            }
        }
        return willOverflow;
    }

    /**
     * Initialized the modifications buffer counter to its maximum value for the current PO
     */
    private void resetModificationsBufferCounter() {
        if (logger.isTraceEnabled()) {
            logger.trace("Modifications buffer counter reset: PREVIOUSVALUE = {}, NEWVALUE = {}",
                    modificationsCounter, modificationsCounterMax);
        }
        modificationsCounter = modificationsCounterMax;
    }

    /**
     * Prepare a select file ApduRequest to be executed following the selection.
     * <p>
     *
     * @param path path from the CURRENT_DF (CURRENT_DF identifier excluded)
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     */
    public int prepareSelectFileCmd(byte[] path, String extraInfo) {

        if (logger.isTraceEnabled()) {
            logger.trace("Select File: PATH = {}", ByteArrayUtil.toHex(path));
        }

        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager
                .addRegularCommand(new SelectFileCmdBuild(calypsoPo.getPoClass(), path));
    }

    /**
     * Prepare a select file ApduRequest to be executed following the selection.
     * <p>
     *
     * @param selectControl provides the navigation case: FIRST, NEXT or CURRENT
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     */
    public int prepareSelectFileCmd(SelectFileCmdBuild.SelectControl selectControl,
            String extraInfo) {
        if (logger.isTraceEnabled()) {
            logger.trace("Navigate: CONTROL = {}", selectControl);
        }

        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager
                .addRegularCommand(new SelectFileCmdBuild(calypsoPo.getPoClass(), selectControl));
    }

    /**
     * Internal method to handle expectedLength checks in public variants
     *
     * @param sfi the sfi top select
     * @param readDataStructureEnum read mode enum to indicate a SINGLE, MULTIPLE or COUNTER read
     * @param firstRecordNumber the record number to read (or first record to read in case of
     *        several records)
     * @param expectedLength the expected length of the record(s)
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if record number &lt; 1
     * @throws IllegalArgumentException - if the request is inconsistent
     */
    private int prepareReadRecordsCmdInternal(byte sfi, ReadDataStructure readDataStructureEnum,
            byte firstRecordNumber, int expectedLength, String extraInfo) {

        /*
         * the readJustOneRecord flag is set to false only in case of multiple read records, in all
         * other cases it is set to true
         */
        boolean readJustOneRecord = readDataStructureEnum != ReadDataStructure.MULTIPLE_RECORD_DATA;

        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager.addRegularCommand(
                new ReadRecordsCmdBuild(calypsoPo.getPoClass(), sfi, readDataStructureEnum,
                        firstRecordNumber, readJustOneRecord, (byte) expectedLength, extraInfo));
    }

    /**
     * Builds a ReadRecords command and add it to the list of commands to be sent with the next
     * process command.
     * <p>
     * The expected length is provided and its value is checked between 1 and 250.
     * <p>
     * Returns the associated response parser.
     *
     * @param sfi the sfi top select
     * @param readDataStructureEnum read mode enum to indicate a SINGLE, MULTIPLE or COUNTER read
     * @param firstRecordNumber the record number to read (or first record to read in case of
     *        several records)
     * @param expectedLength the expected length of the record(s)
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if record number &lt; 1
     * @throws IllegalArgumentException - if the request is inconsistent
     */
    public int prepareReadRecordsCmd(byte sfi, ReadDataStructure readDataStructureEnum,
            byte firstRecordNumber, int expectedLength, String extraInfo) {
        if (expectedLength < 1 || expectedLength > 250) {
            throw new IllegalArgumentException("Bad length.");
        }
        return prepareReadRecordsCmdInternal(sfi, readDataStructureEnum, firstRecordNumber,
                expectedLength, extraInfo);
    }

    /**
     * Builds a ReadRecords command and add it to the list of commands to be sent with the next
     * process command. No expected length is specified, the record output length is handled
     * automatically.
     * <p>
     * Returns the associated response parser.
     *
     * @param sfi the sfi top select
     * @param readDataStructureEnum read mode enum to indicate a SINGLE, MULTIPLE or COUNTER read
     * @param firstRecordNumber the record number to read (or first record to read in case of
     *        several records)
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if record number &lt; 1
     * @throws IllegalArgumentException - if the request is inconsistent
     */
    public int prepareReadRecordsCmd(byte sfi, ReadDataStructure readDataStructureEnum,
            byte firstRecordNumber, String extraInfo) {
        if (poReader.getTransmissionMode() == TransmissionMode.CONTACTS) {
            throw new IllegalArgumentException(
                    "In contacts mode, the expected length must be specified.");
        }
        return prepareReadRecordsCmdInternal(sfi, readDataStructureEnum, firstRecordNumber, 0,
                extraInfo);
    }

    /**
     * Builds an AppendRecord command and add it to the list of commands to be sent with the next
     * process command.
     * <p>
     * Returns the associated response parser.
     *
     * @param sfi the sfi to select
     * @param newRecordData the new record data to write
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if the command is inconsistent
     */
    public int prepareAppendRecordCmd(byte sfi, byte[] newRecordData, String extraInfo) {
        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager.addRegularCommand(
                new AppendRecordCmdBuild(calypsoPo.getPoClass(), sfi, newRecordData, extraInfo));
    }

    /**
     * Builds an UpdateRecord command and add it to the list of commands to be sent with the next
     * process command
     * <p>
     * Returns the associated response parser index.
     *
     * @param sfi the sfi to select
     * @param recordNumber the record number to update
     * @param newRecordData the new record data. If length &lt; RecSize, bytes beyond length are
     *        left unchanged.
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if record number is &lt; 1
     * @throws IllegalArgumentException - if the request is inconsistent
     */
    public int prepareUpdateRecordCmd(byte sfi, byte recordNumber, byte[] newRecordData,
            String extraInfo) {
        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager.addRegularCommand(new UpdateRecordCmdBuild(calypsoPo.getPoClass(),
                sfi, recordNumber, newRecordData, extraInfo));
    }


    /**
     * Builds an WriteRecord command and add it to the list of commands to be sent with the next
     * process command
     * <p>
     * Returns the associated response parser index.
     *
     * @param sfi the sfi to select
     * @param recordNumber the record number to write
     * @param overwriteRecordData the data to overwrite in the record. If length &lt; RecSize, bytes
     *        beyond length are left unchanged.
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if record number is &lt; 1
     * @throws IllegalArgumentException - if the request is inconsistent
     */
    public int prepareWriteRecordCmd(byte sfi, byte recordNumber, byte[] overwriteRecordData,
            String extraInfo) {
        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager.addRegularCommand(new WriteRecordCmdBuild(calypsoPo.getPoClass(),
                sfi, recordNumber, overwriteRecordData, extraInfo));
    }

    /**
     * Builds a Increase command and add it to the list of commands to be sent with the next process
     * command
     * <p>
     * Returns the associated response parser index.
     *
     * @param counterNumber &gt;= 01h: Counters file, number of the counter. 00h: Simulated Counter
     *        file.
     * @param sfi SFI of the file to select or 00h for current EF
     * @param incValue Value to add to the counter (defined as a positive int &lt;= 16777215
     *        [FFFFFFh])
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if the decrement value is out of range
     * @throws IllegalArgumentException - if the command is inconsistent
     */
    public int prepareIncreaseCmd(byte sfi, byte counterNumber, int incValue, String extraInfo) {

        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager.addRegularCommand(new IncreaseCmdBuild(calypsoPo.getPoClass(), sfi,
                counterNumber, incValue, extraInfo));
    }

    /**
     * Builds a Decrease command and add it to the list of commands to be sent with the next process
     * command
     * <p>
     * Returns the associated response parser index.
     *
     * @param counterNumber &gt;= 01h: Counters file, number of the counter. 00h: Simulated Counter
     *        file.
     * @param sfi SFI of the file to select or 00h for current EF
     * @param decValue Value to subtract to the counter (defined as a positive int &lt;= 16777215
     *        [FFFFFFh])
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index (input order, starting at 0)
     * @throws IllegalArgumentException - if the decrement value is out of range
     * @throws IllegalArgumentException - if the command is inconsistent
     */
    public int prepareDecreaseCmd(byte sfi, byte counterNumber, int decValue, String extraInfo) {

        /*
         * create and keep the PoBuilderParser, return the command index
         */

        return poCommandsManager.addRegularCommand(new DecreaseCmdBuild(calypsoPo.getPoClass(), sfi,
                counterNumber, decValue, extraInfo));
    }


    /**
     * Builds a Verify PIN command and add it to the list of commands to be sent with the next
     * process command
     * <p>
     * The PIN code is transmitted in plain text.
     * <p>
     * Returns the associated response parser index.
     *
     * @param pin byte array containing the PIN digits
     * @return the command index (input order, starting at 0)
     * @throws NotSupportedException if the PIN feature is not available for the current PO
     */
    public int prepareVerifyPinPlain(byte[] pin) throws NotSupportedException {
        if (!calypsoPo.hasCalypsoPin()) {
            throw new NotSupportedException(
                    "The PIN code functionality is not supported by this PO");
        }
        if (pin == null || pin.length != 4) {
            throw new IllegalArgumentException("Bad PIN argument");
        }
        /*
         * create and keep the PoBuilderParser, return the command index
         */
        return poCommandsManager.addRegularCommand(
                new VerifyPinCmdBuild(calypsoPo.getPoClass(), PinOperation.SEND_PLAIN_PIN, pin));
    }

    /**
     * Builds a Verify PIN command and add it to the list of commands to be sent with the next
     * process command
     * <p>
     * The PIN code transmission is encrypted.
     * <p>
     * Returns the associated response parser index.
     *
     * @param pin byte array containing the PIN digits
     * @return the command index (input order, starting at 0)
     * @throws NotSupportedException if the PIN feature is not available for the current PO
     */
    public int prepareVerifyPinEncrypted(byte[] pin) throws NotSupportedException {
        if (!calypsoPo.hasCalypsoPin()) {
            throw new NotSupportedException(
                    "The PIN code functionality is not supported by this PO");
        }
        if (pin == null || pin.length != 4) {
            throw new IllegalArgumentException("Bad PIN argument");
        }
        /*
         * add the verify pin encrypted request to the command list managed by the PoCommandsManager
         */
        return poCommandsManager.addVerifyPinEncryptedCommand(calypsoPo, pin);
    }

    /**
     * Get the PIN presentation attempt counter
     * 
     * @param index the command index
     * @return the counter value
     */
    public int getPinAttemptCounter(int index) {
        VerifyPinRespPars verifyPinRespPars =
                (VerifyPinRespPars) (poCommandsManager.getResponseParser(index));
        return verifyPinRespPars.getRemainingAttemptCounter();
    }

    /**
     * Prepares an SV operation or simply retrieves the current SV status
     *
     * @param svOperation informs about the nature of the intended operation: debit or reload
     * @param svAction the type of action: DO a debit or a positive reload, UNDO an undebit or a
     *        negative reload
     * @param logRead specifies whether both log files (reload and debit) are required or whether
     *        only the log file corresponding to the current operation is requested.
     * @return the command index
     * @throws NotSupportedException if the Stored Value is not available for the current PO
     */
    public int prepareSvGet(SvSettings.Operation svOperation, SvSettings.Action svAction,
            SvSettings.LogRead logRead) throws NotSupportedException {
        if (!calypsoPo.hasCalypsoStoredValue()) {
            throw new NotSupportedException(
                    "The Stored Value functionality is not supported by this PO.");
        }
        if (SvSettings.LogRead.ALL.equals(logRead) && !calypsoPo.isRev3_2ModeAvailable()) {
            /**
             * @see Calypso Layer ID 8.09/8.10 (200108): both reload and debit logs are requested
             *      for a non rev3.2 PO add two SvGet commands (for RELOAD then for DEBIT) keep the
             *      index of the second one (used when parsing)
             */
            svDoubleGet = true;
            SvSettings.Operation operation1 =
                    SvSettings.Operation.RELOAD.equals(svOperation) ? SvSettings.Operation.DEBIT
                            : SvSettings.Operation.RELOAD;
            poCommandsManager
                    .addStoredValueCommand(
                            new SvGetCmdBuild(calypsoPo.getPoClass(), calypsoPo.getRevision(),
                                    operation1, "for " + svAction + "/ " + operation1),
                            operation1, svAction);
            return poCommandsManager
                    .addStoredValueCommand(
                            new SvGetCmdBuild(calypsoPo.getPoClass(), calypsoPo.getRevision(),
                                    svOperation, "for " + svAction + "/ " + svOperation),
                            svOperation, svAction);
        } else {
            /*
             * create and keep the requested PoBuilderParser, return the command index
             */
            svDoubleGet = false;
            return poCommandsManager
                    .addStoredValueCommand(
                            new SvGetCmdBuild(calypsoPo.getPoClass(), calypsoPo.getRevision(),
                                    svOperation, "for " + svAction + "/" + svOperation),
                            svOperation, svAction);
        }
    }

    /**
     * Getter for the SV Get output data.
     * <p>
     * Depending on the parameter {@link SvSettings.LogRead} used when calling prepareSvGet and also
     * the type of PO, the output data of this command can come from the result of two seperate
     * APDUs.
     * <p>
     * This method takes this into account and provides a SvGetPoResponse object built from one or
     * two SvGetRespPars.
     * 
     * @return a SvGetPoResponse object
     */
    public SvGetPoResponse getSvGetPoResponse() {
        if (svDoubleGet) {
            /*
             * 2 SV Get commands have been performed: we use the two last parsers from the SV Get
             * parser index
             */
            return new SvGetPoResponse(
                    (SvGetRespPars) (poCommandsManager.getResponseParser(
                            poCommandsManager.getSvGetResponseParserIndex() - 1)),
                    (SvGetRespPars) (poCommandsManager
                            .getResponseParser(poCommandsManager.getSvGetResponseParserIndex())));
        } else {
            /* 1 SV Get command have been performed */
            return new SvGetPoResponse((SvGetRespPars) (poCommandsManager
                    .getResponseParser(poCommandsManager.getSvGetResponseParserIndex())));
        }
    }

    /**
     * Prepares an SV reload (increasing the current SV balance)
     * <p>
     * Note: the key used is the reload key
     *
     * @param amount the value to be reloaded, positive or negative integer in the range
     *        -8388608..8388607
     * @param date 2-byte free value
     * @param time 2-byte free value
     * @param free 2-byte free value
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index
     * @throws KeypleCalypsoSvSecurityException in case of security issue
     * @throws KeypleReaderException in case of failure during the SAM communication
     */
    private int prepareSvReloadPriv(int amount, byte[] date, byte[] time, byte[] free,
            String extraInfo) throws KeypleReaderException {
        // create the initial builder with the application data
        SvReloadCmdBuild svReloadCmdBuild =
                new SvReloadCmdBuild(calypsoPo.getPoClass(), calypsoPo.getRevision(), amount,
                        ((SvGetRespPars) poCommandsManager
                                .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()))
                                        .getCurrentKVC(),
                        date, time, free);

        // get the security data from the SAM
        byte[] svReloadComplementaryData = samCommandsProcessor.getSvReloadComplementaryData(
                (SvGetRespPars) poCommandsManager
                        .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()),
                svReloadCmdBuild);

        // finalize the SvReload command builder with the data provided by the SAM
        svReloadCmdBuild.finalizeBuilder(svReloadComplementaryData, extraInfo);

        /*
         * create and keep the PoBuilderParser, return the command index
         */
        return poCommandsManager.addStoredValueCommand(svReloadCmdBuild,
                SvSettings.Operation.RELOAD, SvSettings.Action.DO);
    }

    /**
     * Prepares an SV reload (increasing the current SV balance)
     * <p>
     * Note: the key used is the reload key
     *
     * @param amount the value to be reloaded, positive integer in the range 0..8388607 for a DO
     *        action, in the range 0..8388608 for an UNDO action.
     * @param date 2-byte free value
     * @param time 2-byte free value
     * @param free 2-byte free value
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index
     * @throws KeypleCalypsoSvSecurityException in case of security issue
     * @throws KeypleReaderException in case of failure during the SAM communication
     */
    public int prepareSvReload(int amount, byte[] date, byte[] time, byte[] free, String extraInfo)
            throws KeypleReaderException {
        if (SvSettings.Action.UNDO.equals(poCommandsManager.getSvAction())) {
            amount = -amount;
        }
        return prepareSvReloadPriv(amount, date, time, free, extraInfo);
    }

    /**
     * Prepares an SV reload (increasing the current SV balance)
     * <p>
     * Note: the key used is the reload key
     *
     * @param amount the value to be reloaded, positive integer in the range 0..8388607 for a DO
     *        action, in the range 0..8388608 for an UNDO action.
     * @return the command index
     * @throws KeypleCalypsoSvSecurityException in case of security issue
     * @throws KeypleReaderException in case of failure during the SAM communication
     */
    public int prepareSvReload(int amount) throws KeypleReaderException {
        byte[] zero = {0x00, 0x00};
        String extraInfo = poCommandsManager.getSvAction().toString() + " reload " + amount;
        return prepareSvReload(amount, zero, zero, zero, extraInfo);
    }

    /**
     * Prepares an SV debit.
     * <p>
     * It consists in decreasing the current balance of the SV by a certain amount.
     * <p>
     * Note: the key used is the debit key
     *
     * @param amount the amount to be subtracted, positive integer in the range 0..32767
     * @param date 2-byte free value
     * @param time 2-byte free value
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index
     * @throws KeypleCalypsoSvException if the balance were to turn negative and the negative
     *         balance is not allowed in the settings.
     * @throws KeypleCalypsoSvSecurityException in case of security issue
     * @throws KeypleCalypsoSvException if the resulting balance becomes negative and this is not
     *         allowed (see allowSvNegativeBalances)
     * @throws KeypleReaderException in case of failure during the SAM communication
     */
    private int prepareSvDebitPriv(int amount, byte[] date, byte[] time,
            SvSettings.NegativeBalance negativeBalance, String extraInfo)
            throws KeypleReaderException {

        if (SvSettings.NegativeBalance.FORBIDDEN.equals(negativeBalance)
                && (((SvGetRespPars) poCommandsManager
                        .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()))
                                .getBalance()
                        - amount) < 0) {
            throw new KeypleCalypsoSvException("Negative balances not allowed.");
        }

        // create the initial builder with the application data
        SvDebitCmdBuild svDebitCmdBuild =
                new SvDebitCmdBuild(calypsoPo.getPoClass(), calypsoPo.getRevision(), amount,
                        ((SvGetRespPars) poCommandsManager
                                .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()))
                                        .getCurrentKVC(),
                        date, time);

        // get the security data from the SAM
        byte[] svDebitComplementaryData = samCommandsProcessor.getSvDebitComplementaryData(
                (SvGetRespPars) poCommandsManager
                        .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()),
                svDebitCmdBuild);

        // finalize the SvReload command builder with the data provided by the SAM
        svDebitCmdBuild.finalizeBuilder(svDebitComplementaryData, extraInfo);

        /*
         * create and keep the PoBuilderParser, return the command index
         */
        return poCommandsManager.addStoredValueCommand(svDebitCmdBuild, SvSettings.Operation.DEBIT,
                SvSettings.Action.DO);
    }

    /**
     * Prepares an SV Undebit (partially or totally cancels the last SV debit command).
     * <p>
     * It consists in canceling a previous debit.
     * <p>
     * Note: the key used is the debit key
     *
     * @param amount the amount to be subtracted, positive integer in the range 0..32767
     * @param date 2-byte free value
     * @param time 2-byte free value
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index
     * @throws KeypleCalypsoSvSecurityException in case of security issue
     * @throws KeypleReaderException in case of failure during the SAM communication TODO add
     *         specific exception
     */
    private int prepareSvUndebitPriv(int amount, byte[] date, byte[] time, String extraInfo)
            throws KeypleReaderException {
        // create the initial builder with the application data
        SvUndebitCmdBuild svUndebitCmdBuild =
                new SvUndebitCmdBuild(calypsoPo.getPoClass(), calypsoPo.getRevision(), amount,
                        ((SvGetRespPars) poCommandsManager
                                .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()))
                                        .getCurrentKVC(),
                        date, time);

        // get the security data from the SAM
        byte[] svUndebitComplementaryData = samCommandsProcessor.getSvUndebitComplementaryData(
                (SvGetRespPars) poCommandsManager
                        .getResponseParser(poCommandsManager.getSvGetResponseParserIndex()),
                svUndebitCmdBuild);

        // finalize the SvReload command builder with the data provided by the SAM
        svUndebitCmdBuild.finalizeBuilder(svUndebitComplementaryData, extraInfo);

        /*
         * create and keep the PoBuilderParser, return the command index
         */
        return poCommandsManager.addStoredValueCommand(svUndebitCmdBuild,
                SvSettings.Operation.DEBIT, SvSettings.Action.UNDO);
    }

    /**
     * Prepares an SV debit or Undebit (partially or totally cancels the last SV debit command).
     * <p>
     * It consists in decreasing the current balance of the SV by a certain amount or canceling a
     * previous debit.
     * <p>
     * Note: the key used is the debit key
     *
     * @param amount the amount to be subtracted or added, positive integer in the range 0..32767
     *        when subtracted and 0..32768 when added.
     * @param date 2-byte free value
     * @param time 2-byte free value
     * @param negativeBalance indicates whether negative balance is allowed or not
     * @param extraInfo extra information included in the logs (can be null or empty)
     * @return the command index
     * @throws KeypleCalypsoSvException if the balance were to turn negative and the negative
     *         balance is not allowed in the settings.
     * @throws KeypleReaderException in case of failure during the SAM communication
     */
    public int prepareSvDebit(int amount, byte[] date, byte[] time,
            SvSettings.NegativeBalance negativeBalance, String extraInfo)
            throws KeypleReaderException {
        if (SvSettings.Action.DO.equals(poCommandsManager.getSvAction())) {
            return prepareSvDebitPriv(amount, date, time, negativeBalance, extraInfo);
        } else {
            return prepareSvUndebitPriv(amount, date, time, extraInfo);
        }
    }

    /**
     * Prepares an SV debit or Undebit (partially or totally cancels the last SV debit command).
     * <p>
     * It consists in decreasing the current balance of the SV by a certain amount or canceling a
     * previous debit.
     * <p>
     * The information fields such as date and time are set to 0. The extraInfo field propagated in
     * Logs are automatically generated with the type of transaction and amount.
     * <p>
     * Operations that would result in a negative balance are forbidden (SV Exception raised).
     * <p>
     * Note: the key used is the debit key
     *
     * @param amount the amount to be subtracted or added, positive integer in the range 0..32767
     *        when subtracted and 0..32768 when added.
     * @return the command index
     * @throws KeypleCalypsoSvException if the balance were to turn negative and the negative
     *         balance is not allowed in the settings.
     * @throws KeypleReaderException in case of failure during the SAM communication
     */
    public int prepareSvDebit(int amount) throws KeypleReaderException {
        byte[] zero = {0x00, 0x00};
        String extraInfo = poCommandsManager.getSvAction().toString() + " debit " + amount;
        return prepareSvDebit(amount, zero, zero, SvSettings.NegativeBalance.FORBIDDEN, extraInfo);
    }

    /**
     * Get the response parser matching the prepared command for which the index is provided
     * 
     * @param commandIndex the index of the parser to be retrieved
     * @return the corresponding command parser
     */
    public AbstractApduResponseParser getResponseParser(int commandIndex) {
        return poCommandsManager.getResponseParser(commandIndex);
    }

    private void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * @return a string describing the last error encountered
     */
    public String getLastError() {
        return lastError;
    }
}
