/*
 * Copyright 2018-2020 SIP3.IO, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.sip3.salto.ce.sip

import io.mockk.every
import io.mockk.mockk
import io.sip3.salto.ce.Attributes
import io.sip3.salto.ce.domain.Address
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SipSessionTest {

    companion object {

        val NOW = System.currentTimeMillis()

        val INVITE_TRANSACTION = SipTransaction().apply {
            createdAt = NOW - 10000
            terminatedAt = NOW - 5000
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            callId = "callId"
            caller = "caller"
            callee = "callee"
            request = mockk()
            response = mockk()
        }

        val BYE_TRANSACTION = SipTransaction().apply {
            createdAt = NOW - 100
            terminatedAt = NOW
            srcAddr = Address().apply {
                addr = "127.0.0.1"
                port = 5060
            }
            dstAddr = Address().apply {
                addr = "127.0.0.2"
                port = 5061
            }
            callId = "callId"
            caller = "caller"
            callee = "callee"
            request = mockk()
            response = mockk()
        }
    }

    @Test
    fun `Add INVITE transaction and check 'redirected' session`() {
        val session = SipSession()

        every { INVITE_TRANSACTION.response?.statusCode } returns 301
        session.addInviteTransaction(INVITE_TRANSACTION)

        assertEquals(NOW - 10000, session.createdAt)
        assertEquals(NOW - 5000, session.terminatedAt)
        assertEquals(INVITE_TRANSACTION.srcAddr.addr, session.srcAddr.addr)
        assertEquals(INVITE_TRANSACTION.srcAddr.port, session.srcAddr.port)
        assertEquals(INVITE_TRANSACTION.dstAddr.addr, session.dstAddr.addr)
        assertEquals(INVITE_TRANSACTION.dstAddr.port, session.dstAddr.port)
        assertEquals("callId", session.callId)
        assertEquals("callee", session.callee)
        assertEquals("caller", session.caller)
        assertEquals(SipSession.REDIRECTED, session.state)
    }

    @Test
    fun `Add INVITE transaction and check 'canceled' session`() {
        val session = SipSession()

        every { INVITE_TRANSACTION.response?.statusCode } returns 487
        session.addInviteTransaction(INVITE_TRANSACTION)

        assertEquals(NOW - 10000, session.createdAt)
        assertEquals(NOW - 5000, session.terminatedAt)
        assertEquals(INVITE_TRANSACTION.srcAddr.addr, session.srcAddr.addr)
        assertEquals(INVITE_TRANSACTION.srcAddr.port, session.srcAddr.port)
        assertEquals(INVITE_TRANSACTION.dstAddr.addr, session.dstAddr.addr)
        assertEquals(INVITE_TRANSACTION.dstAddr.port, session.dstAddr.port)
        assertEquals("callId", session.callId)
        assertEquals("callee", session.callee)
        assertEquals("caller", session.caller)
        assertEquals(SipSession.CANCELED, session.state)
    }

    @Test
    fun `Add INVITE transaction and check 'failed' session`() {
        val session = SipSession()

        every { INVITE_TRANSACTION.response?.statusCode } returns 404
        session.addInviteTransaction(INVITE_TRANSACTION)

        assertEquals(NOW - 10000, session.createdAt)
        assertEquals(NOW - 5000, session.terminatedAt)
        assertEquals(INVITE_TRANSACTION.srcAddr.addr, session.srcAddr.addr)
        assertEquals(INVITE_TRANSACTION.srcAddr.port, session.srcAddr.port)
        assertEquals(INVITE_TRANSACTION.dstAddr.addr, session.dstAddr.addr)
        assertEquals(INVITE_TRANSACTION.dstAddr.port, session.dstAddr.port)
        assertEquals("callId", session.callId)
        assertEquals("callee", session.callee)
        assertEquals("caller", session.caller)
        assertEquals(SipSession.FAILED, session.state)
        assertEquals("404", session.attributes[Attributes.error_code])
    }

    @Test
    fun `Add INVITE transaction and check 'unauthorized' session`() {
        val session = SipSession()

        every { INVITE_TRANSACTION.response?.statusCode } returns 407
        session.addInviteTransaction(INVITE_TRANSACTION)

        assertEquals(NOW - 10000, session.createdAt)
        assertNull(session.terminatedAt)
        assertEquals(INVITE_TRANSACTION.srcAddr.addr, session.srcAddr.addr)
        assertEquals(INVITE_TRANSACTION.srcAddr.port, session.srcAddr.port)
        assertEquals(INVITE_TRANSACTION.dstAddr.addr, session.dstAddr.addr)
        assertEquals(INVITE_TRANSACTION.dstAddr.port, session.dstAddr.port)
        assertEquals("callId", session.callId)
        assertEquals("callee", session.callee)
        assertEquals("caller", session.caller)
        assertEquals(SipSession.UNAUTHORIZED, session.state)
    }

    @Test
    fun `Add multiple transactions as a typical flow and check 'answered' session`() {
        val session = SipSession()

        every { INVITE_TRANSACTION.response?.statusCode } returns 407
        session.addInviteTransaction(INVITE_TRANSACTION)

        every { INVITE_TRANSACTION.response?.statusCode } returns 200
        session.addInviteTransaction(INVITE_TRANSACTION)

        session.addByeTransaction(BYE_TRANSACTION)

        assertEquals(NOW - 10000, session.createdAt)
        assertEquals(NOW , session.terminatedAt)
        assertEquals(INVITE_TRANSACTION.srcAddr.addr, session.srcAddr.addr)
        assertEquals(INVITE_TRANSACTION.srcAddr.port, session.srcAddr.port)
        assertEquals(INVITE_TRANSACTION.dstAddr.addr, session.dstAddr.addr)
        assertEquals(INVITE_TRANSACTION.dstAddr.port, session.dstAddr.port)
        assertEquals("callId", session.callId)
        assertEquals("callee", session.callee)
        assertEquals("caller", session.caller)
        assertEquals(SipSession.ANSWERED, session.state)
        assertEquals(4900, session.duration)
    }
}