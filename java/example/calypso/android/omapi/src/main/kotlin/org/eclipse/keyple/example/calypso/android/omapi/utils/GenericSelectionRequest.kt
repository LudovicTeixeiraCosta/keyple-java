/********************************************************************************
 * Copyright (c) 2020 Calypso Networks Association https://www.calypsonet-asso.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information regarding copyright
 * ownership.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.keyple.example.calypso.android.omapi.utils

import org.eclipse.keyple.core.command.AbstractApduCommandBuilder
import org.eclipse.keyple.core.selection.AbstractMatchingSe
import org.eclipse.keyple.core.selection.AbstractSeSelectionRequest
import org.eclipse.keyple.core.seproxy.CardSelector
import org.eclipse.keyple.core.seproxy.message.CardResponse

class GenericSeSelectionRequest(cardSelector: CardSelector) : AbstractSeSelectionRequest<AbstractApduCommandBuilder>(cardSelector) {
    override fun parse(cardResponse: CardResponse): AbstractMatchingSe {
        class GenericMatchingSe(
            selectionResponse: CardResponse
        ) : AbstractMatchingSe(selectionResponse)
        return GenericMatchingSe(cardResponse)
    }
}
