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

import io.sip3.salto.ce.util.callId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SipMessageParserTest {

    companion object {

        // Single SIP message
        val PACKET_1 = byteArrayOf(
                0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(), 0x30.toByte(),
                0x20.toByte(), 0x32.toByte(), 0x30.toByte(), 0x30.toByte(), 0x20.toByte(), 0x4f.toByte(), 0x4b.toByte(),
                0x0d.toByte(), 0x0a.toByte(), 0x56.toByte(), 0x69.toByte(), 0x61.toByte(), 0x3a.toByte(), 0x20.toByte(),
                0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(), 0x30.toByte(),
                0x2f.toByte(), 0x55.toByte(), 0x44.toByte(), 0x50.toByte(), 0x20.toByte(), 0x31.toByte(), 0x30.toByte(),
                0x2e.toByte(), 0x31.toByte(), 0x37.toByte(), 0x37.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x34.toByte(),
                0x31.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x36.toByte(), 0x34.toByte(), 0x3a.toByte(), 0x35.toByte(),
                0x30.toByte(), 0x38.toByte(), 0x30.toByte(), 0x3b.toByte(), 0x62.toByte(), 0x72.toByte(), 0x61.toByte(),
                0x6e.toByte(), 0x63.toByte(), 0x68.toByte(), 0x3d.toByte(), 0x7a.toByte(), 0x39.toByte(), 0x68.toByte(),
                0x47.toByte(), 0x34.toByte(), 0x62.toByte(), 0x4b.toByte(), 0x6e.toByte(), 0x32.toByte(), 0x6e.toByte(),
                0x36.toByte(), 0x6c.toByte(), 0x76.toByte(), 0x33.toByte(), 0x30.toByte(), 0x37.toByte(), 0x38.toByte(),
                0x72.toByte(), 0x62.toByte(), 0x71.toByte(), 0x70.toByte(), 0x33.toByte(), 0x31.toByte(), 0x62.toByte(),
                0x6b.toByte(), 0x31.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x46.toByte(), 0x72.toByte(),
                0x6f.toByte(), 0x6d.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x73.toByte(), 0x69.toByte(), 0x70.toByte(),
                0x3a.toByte(), 0x37.toByte(), 0x39.toByte(), 0x32.toByte(), 0x35.toByte(), 0x30.toByte(), 0x31.toByte(),
                0x34.toByte(), 0x30.toByte(), 0x37.toByte(), 0x37.toByte(), 0x37.toByte(), 0x40.toByte(), 0x6d.toByte(),
                0x75.toByte(), 0x6c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x66.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2e.toByte(), 0x72.toByte(), 0x75.toByte(), 0x3b.toByte(), 0x74.toByte(), 0x61.toByte(), 0x67.toByte(),
                0x3d.toByte(), 0x30.toByte(), 0x42.toByte(), 0x37.toByte(), 0x30.toByte(), 0x33.toByte(), 0x32.toByte(),
                0x34.toByte(), 0x36.toByte(), 0x33.toByte(), 0x31.toByte(), 0x33.toByte(), 0x35.toByte(), 0x33.toByte(),
                0x36.toByte(), 0x34.toByte(), 0x31.toByte(), 0x46.toByte(), 0x41.toByte(), 0x39.toByte(), 0x35.toByte(),
                0x41.toByte(), 0x33.toByte(), 0x33.toByte(), 0x43.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x54.toByte(),
                0x6f.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x3c.toByte(), 0x73.toByte(), 0x69.toByte(), 0x70.toByte(),
                0x3a.toByte(), 0x37.toByte(), 0x39.toByte(), 0x30.toByte(), 0x36.toByte(), 0x37.toByte(), 0x35.toByte(),
                0x38.toByte(), 0x37.toByte(), 0x32.toByte(), 0x34.toByte(), 0x38.toByte(), 0x40.toByte(), 0x6d.toByte(),
                0x75.toByte(), 0x6c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x66.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2e.toByte(), 0x72.toByte(), 0x75.toByte(), 0x3a.toByte(), 0x35.toByte(), 0x30.toByte(), 0x36.toByte(),
                0x30.toByte(), 0x3e.toByte(), 0x3b.toByte(), 0x74.toByte(), 0x61.toByte(), 0x67.toByte(), 0x3d.toByte(),
                0x65.toByte(), 0x44.toByte(), 0x5a.toByte(), 0x37.toByte(), 0x31.toByte(), 0x56.toByte(), 0x58.toByte(),
                0x67.toByte(), 0x44.toByte(), 0x55.toByte(), 0x30.toByte(), 0x57.toByte(), 0x2e.toByte(), 0x43.toByte(),
                0x38.toByte(), 0x35.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(), 0x61.toByte(), 0x6c.toByte(),
                0x6c.toByte(), 0x2d.toByte(), 0x49.toByte(), 0x44.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x30.toByte(),
                0x32.toByte(), 0x31.toByte(), 0x31.toByte(), 0x30.toByte(), 0x37.toByte(), 0x30.toByte(), 0x43.toByte(),
                0x35.toByte(), 0x36.toByte(), 0x38.toByte(), 0x31.toByte(), 0x34.toByte(), 0x30.toByte(), 0x30.toByte(),
                0x30.toByte(), 0x30.toByte(), 0x45.toByte(), 0x45.toByte(), 0x41.toByte(), 0x30.toByte(), 0x31.toByte(),
                0x46.toByte(), 0x42.toByte(), 0x40.toByte(), 0x53.toByte(), 0x46.toByte(), 0x45.toByte(), 0x53.toByte(),
                0x49.toByte(), 0x50.toByte(), 0x31.toByte(), 0x2d.toByte(), 0x69.toByte(), 0x64.toByte(), 0x32.toByte(),
                0x2d.toByte(), 0x65.toByte(), 0x78.toByte(), 0x74.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(),
                0x53.toByte(), 0x65.toByte(), 0x71.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x32.toByte(), 0x20.toByte(),
                0x50.toByte(), 0x52.toByte(), 0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(),
                0x2d.toByte(), 0x4c.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x67.toByte(), 0x74.toByte(), 0x68.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x53.toByte(), 0x75.toByte(),
                0x70.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(), 0x65.toByte(), 0x64.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x31.toByte(), 0x30.toByte(), 0x30.toByte(), 0x72.toByte(), 0x65.toByte(),
                0x6c.toByte(), 0x2c.toByte(), 0x70.toByte(), 0x72.toByte(), 0x65.toByte(), 0x63.toByte(), 0x6f.toByte(),
                0x6e.toByte(), 0x64.toByte(), 0x69.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x72.toByte(), 0x0d.toByte(),
                0x0a.toByte(), 0x41.toByte(), 0x6c.toByte(), 0x6c.toByte(), 0x6f.toByte(), 0x77.toByte(), 0x3a.toByte(),
                0x20.toByte(), 0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x2c.toByte(), 0x42.toByte(), 0x59.toByte(),
                0x45.toByte(), 0x2c.toByte(), 0x43.toByte(), 0x41.toByte(), 0x4e.toByte(), 0x43.toByte(), 0x45.toByte(),
                0x4c.toByte(), 0x2c.toByte(), 0x49.toByte(), 0x4e.toByte(), 0x46.toByte(), 0x4f.toByte(), 0x2c.toByte(),
                0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(), 0x54.toByte(), 0x45.toByte(), 0x2c.toByte(),
                0x4e.toByte(), 0x4f.toByte(), 0x54.toByte(), 0x49.toByte(), 0x46.toByte(), 0x59.toByte(), 0x2c.toByte(),
                0x4f.toByte(), 0x50.toByte(), 0x54.toByte(), 0x49.toByte(), 0x4f.toByte(), 0x4e.toByte(), 0x53.toByte(),
                0x2c.toByte(), 0x50.toByte(), 0x52.toByte(), 0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x2c.toByte(),
                0x55.toByte(), 0x50.toByte(), 0x44.toByte(), 0x41.toByte(), 0x54.toByte(), 0x45.toByte(), 0x0d.toByte(),
                0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte()
        )

        // Multiple SIP messages (including SIP INVITE with SDP and ISUP)
        val PACKET_2 = byteArrayOf(
                0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(), 0x30.toByte(),
                0x20.toByte(), 0x32.toByte(), 0x30.toByte(), 0x30.toByte(), 0x20.toByte(), 0x4f.toByte(), 0x4b.toByte(),
                0x0d.toByte(), 0x0a.toByte(), 0x56.toByte(), 0x69.toByte(), 0x61.toByte(), 0x3a.toByte(), 0x20.toByte(),
                0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(), 0x30.toByte(),
                0x2f.toByte(), 0x55.toByte(), 0x44.toByte(), 0x50.toByte(), 0x20.toByte(), 0x31.toByte(), 0x30.toByte(),
                0x2e.toByte(), 0x31.toByte(), 0x37.toByte(), 0x37.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x34.toByte(),
                0x31.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x36.toByte(), 0x34.toByte(), 0x3a.toByte(), 0x35.toByte(),
                0x30.toByte(), 0x38.toByte(), 0x30.toByte(), 0x3b.toByte(), 0x62.toByte(), 0x72.toByte(), 0x61.toByte(),
                0x6e.toByte(), 0x63.toByte(), 0x68.toByte(), 0x3d.toByte(), 0x7a.toByte(), 0x39.toByte(), 0x68.toByte(),
                0x47.toByte(), 0x34.toByte(), 0x62.toByte(), 0x4b.toByte(), 0x6e.toByte(), 0x32.toByte(), 0x6e.toByte(),
                0x36.toByte(), 0x6c.toByte(), 0x76.toByte(), 0x33.toByte(), 0x30.toByte(), 0x37.toByte(), 0x38.toByte(),
                0x72.toByte(), 0x62.toByte(), 0x71.toByte(), 0x70.toByte(), 0x33.toByte(), 0x31.toByte(), 0x62.toByte(),
                0x6b.toByte(), 0x31.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x46.toByte(), 0x72.toByte(),
                0x6f.toByte(), 0x6d.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x73.toByte(), 0x69.toByte(), 0x70.toByte(),
                0x3a.toByte(), 0x37.toByte(), 0x39.toByte(), 0x32.toByte(), 0x35.toByte(), 0x30.toByte(), 0x31.toByte(),
                0x34.toByte(), 0x30.toByte(), 0x37.toByte(), 0x37.toByte(), 0x37.toByte(), 0x40.toByte(), 0x6d.toByte(),
                0x75.toByte(), 0x6c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x66.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2e.toByte(), 0x72.toByte(), 0x75.toByte(), 0x3b.toByte(), 0x74.toByte(), 0x61.toByte(), 0x67.toByte(),
                0x3d.toByte(), 0x30.toByte(), 0x42.toByte(), 0x37.toByte(), 0x30.toByte(), 0x33.toByte(), 0x32.toByte(),
                0x34.toByte(), 0x36.toByte(), 0x33.toByte(), 0x31.toByte(), 0x33.toByte(), 0x35.toByte(), 0x33.toByte(),
                0x36.toByte(), 0x34.toByte(), 0x31.toByte(), 0x46.toByte(), 0x41.toByte(), 0x39.toByte(), 0x35.toByte(),
                0x41.toByte(), 0x33.toByte(), 0x33.toByte(), 0x43.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x54.toByte(),
                0x6f.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x3c.toByte(), 0x73.toByte(), 0x69.toByte(), 0x70.toByte(),
                0x3a.toByte(), 0x37.toByte(), 0x39.toByte(), 0x30.toByte(), 0x36.toByte(), 0x37.toByte(), 0x35.toByte(),
                0x38.toByte(), 0x37.toByte(), 0x32.toByte(), 0x34.toByte(), 0x38.toByte(), 0x40.toByte(), 0x6d.toByte(),
                0x75.toByte(), 0x6c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x66.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2e.toByte(), 0x72.toByte(), 0x75.toByte(), 0x3a.toByte(), 0x35.toByte(), 0x30.toByte(), 0x36.toByte(),
                0x30.toByte(), 0x3e.toByte(), 0x3b.toByte(), 0x74.toByte(), 0x61.toByte(), 0x67.toByte(), 0x3d.toByte(),
                0x65.toByte(), 0x44.toByte(), 0x5a.toByte(), 0x37.toByte(), 0x31.toByte(), 0x56.toByte(), 0x58.toByte(),
                0x67.toByte(), 0x44.toByte(), 0x55.toByte(), 0x30.toByte(), 0x57.toByte(), 0x2e.toByte(), 0x43.toByte(),
                0x38.toByte(), 0x35.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(), 0x61.toByte(), 0x6c.toByte(),
                0x6c.toByte(), 0x2d.toByte(), 0x49.toByte(), 0x44.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x30.toByte(),
                0x32.toByte(), 0x31.toByte(), 0x31.toByte(), 0x30.toByte(), 0x37.toByte(), 0x30.toByte(), 0x43.toByte(),
                0x35.toByte(), 0x36.toByte(), 0x38.toByte(), 0x31.toByte(), 0x34.toByte(), 0x30.toByte(), 0x30.toByte(),
                0x30.toByte(), 0x30.toByte(), 0x45.toByte(), 0x45.toByte(), 0x41.toByte(), 0x30.toByte(), 0x31.toByte(),
                0x46.toByte(), 0x42.toByte(), 0x40.toByte(), 0x53.toByte(), 0x46.toByte(), 0x45.toByte(), 0x53.toByte(),
                0x49.toByte(), 0x50.toByte(), 0x31.toByte(), 0x2d.toByte(), 0x69.toByte(), 0x64.toByte(), 0x32.toByte(),
                0x2d.toByte(), 0x65.toByte(), 0x78.toByte(), 0x74.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(),
                0x53.toByte(), 0x65.toByte(), 0x71.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x32.toByte(), 0x20.toByte(),
                0x50.toByte(), 0x52.toByte(), 0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(),
                0x2d.toByte(), 0x4c.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x67.toByte(), 0x74.toByte(), 0x68.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x53.toByte(), 0x75.toByte(),
                0x70.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(), 0x65.toByte(), 0x64.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x31.toByte(), 0x30.toByte(), 0x30.toByte(), 0x72.toByte(), 0x65.toByte(),
                0x6c.toByte(), 0x2c.toByte(), 0x70.toByte(), 0x72.toByte(), 0x65.toByte(), 0x63.toByte(), 0x6f.toByte(),
                0x6e.toByte(), 0x64.toByte(), 0x69.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x72.toByte(), 0x0d.toByte(),
                0x0a.toByte(), 0x41.toByte(), 0x6c.toByte(), 0x6c.toByte(), 0x6f.toByte(), 0x77.toByte(), 0x3a.toByte(),
                0x20.toByte(), 0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x2c.toByte(), 0x42.toByte(), 0x59.toByte(),
                0x45.toByte(), 0x2c.toByte(), 0x43.toByte(), 0x41.toByte(), 0x4e.toByte(), 0x43.toByte(), 0x45.toByte(),
                0x4c.toByte(), 0x2c.toByte(), 0x49.toByte(), 0x4e.toByte(), 0x46.toByte(), 0x4f.toByte(), 0x2c.toByte(),
                0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(), 0x54.toByte(), 0x45.toByte(), 0x2c.toByte(),
                0x4e.toByte(), 0x4f.toByte(), 0x54.toByte(), 0x49.toByte(), 0x46.toByte(), 0x59.toByte(), 0x2c.toByte(),
                0x4f.toByte(), 0x50.toByte(), 0x54.toByte(), 0x49.toByte(), 0x4f.toByte(), 0x4e.toByte(), 0x53.toByte(),
                0x2c.toByte(), 0x50.toByte(), 0x52.toByte(), 0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x2c.toByte(),
                0x55.toByte(), 0x50.toByte(), 0x44.toByte(), 0x41.toByte(), 0x54.toByte(), 0x45.toByte(), 0x0d.toByte(),
                0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(), 0x54.toByte(), 0x45.toByte(), 0x20.toByte(),
                0x73.toByte(), 0x69.toByte(), 0x70.toByte(), 0x3a.toByte(), 0x2b.toByte(), 0x37.toByte(), 0x39.toByte(),
                0x32.toByte(), 0x36.toByte(), 0x32.toByte(), 0x39.toByte(), 0x30.toByte(), 0x39.toByte(), 0x34.toByte(),
                0x30.toByte(), 0x30.toByte(), 0x40.toByte(), 0x31.toByte(), 0x30.toByte(), 0x2e.toByte(), 0x31.toByte(),
                0x37.toByte(), 0x37.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x34.toByte(), 0x31.toByte(), 0x2e.toByte(),
                0x31.toByte(), 0x36.toByte(), 0x36.toByte(), 0x3b.toByte(), 0x75.toByte(), 0x73.toByte(), 0x65.toByte(),
                0x72.toByte(), 0x3d.toByte(), 0x70.toByte(), 0x68.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x65.toByte(),
                0x20.toByte(), 0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(),
                0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x56.toByte(), 0x69.toByte(), 0x61.toByte(), 0x3a.toByte(),
                0x20.toByte(), 0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x32.toByte(), 0x2e.toByte(),
                0x30.toByte(), 0x2f.toByte(), 0x55.toByte(), 0x44.toByte(), 0x50.toByte(), 0x20.toByte(), 0x31.toByte(),
                0x30.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x39.toByte(), 0x30.toByte(), 0x2e.toByte(), 0x39.toByte(),
                0x30.toByte(), 0x2e.toByte(), 0x38.toByte(), 0x33.toByte(), 0x3a.toByte(), 0x35.toByte(), 0x30.toByte(),
                0x36.toByte(), 0x30.toByte(), 0x3b.toByte(), 0x62.toByte(), 0x72.toByte(), 0x61.toByte(), 0x6e.toByte(),
                0x63.toByte(), 0x68.toByte(), 0x3d.toByte(), 0x7a.toByte(), 0x39.toByte(), 0x68.toByte(), 0x47.toByte(),
                0x34.toByte(), 0x62.toByte(), 0x4b.toByte(), 0x62.toByte(), 0x76.toByte(), 0x69.toByte(), 0x72.toByte(),
                0x35.toByte(), 0x64.toByte(), 0x31.toByte(), 0x30.toByte(), 0x63.toByte(), 0x6f.toByte(), 0x76.toByte(),
                0x37.toByte(), 0x6e.toByte(), 0x6d.toByte(), 0x62.toByte(), 0x69.toByte(), 0x65.toByte(), 0x6a.toByte(),
                0x34.toByte(), 0x30.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(),
                0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x2d.toByte(),
                0x4c.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x67.toByte(), 0x74.toByte(), 0x68.toByte(), 0x3a.toByte(),
                0x20.toByte(), 0x36.toByte(), 0x36.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x46.toByte(),
                0x72.toByte(), 0x6f.toByte(), 0x6d.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x3c.toByte(), 0x73.toByte(),
                0x69.toByte(), 0x70.toByte(), 0x3a.toByte(), 0x2b.toByte(), 0x37.toByte(), 0x39.toByte(), 0x31.toByte(),
                0x31.toByte(), 0x36.toByte(), 0x34.toByte(), 0x33.toByte(), 0x39.toByte(), 0x39.toByte(), 0x39.toByte(),
                0x39.toByte(), 0x40.toByte(), 0x6e.toByte(), 0x6f.toByte(), 0x76.toByte(), 0x67.toByte(), 0x6f.toByte(),
                0x72.toByte(), 0x6f.toByte(), 0x64.toByte(), 0x2e.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x67.toByte(),
                0x61.toByte(), 0x66.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x2e.toByte(), 0x72.toByte(), 0x75.toByte(),
                0x3b.toByte(), 0x75.toByte(), 0x73.toByte(), 0x65.toByte(), 0x72.toByte(), 0x3d.toByte(), 0x70.toByte(),
                0x68.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x65.toByte(), 0x3e.toByte(), 0x3b.toByte(), 0x74.toByte(),
                0x61.toByte(), 0x67.toByte(), 0x3d.toByte(), 0x6a.toByte(), 0x55.toByte(), 0x34.toByte(), 0x5f.toByte(),
                0x58.toByte(), 0x5f.toByte(), 0x44.toByte(), 0x42.toByte(), 0x56.toByte(), 0x5a.toByte(), 0x66.toByte(),
                0x59.toByte(), 0x42.toByte(), 0x32.toByte(), 0x59.toByte(), 0x35.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x54.toByte(), 0x6f.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x3c.toByte(), 0x73.toByte(), 0x69.toByte(),
                0x70.toByte(), 0x3a.toByte(), 0x2b.toByte(), 0x37.toByte(), 0x39.toByte(), 0x32.toByte(), 0x36.toByte(),
                0x32.toByte(), 0x39.toByte(), 0x30.toByte(), 0x39.toByte(), 0x34.toByte(), 0x30.toByte(), 0x30.toByte(),
                0x40.toByte(), 0x53.toByte(), 0x49.toByte(), 0x50.toByte(), 0x4d.toByte(), 0x53.toByte(), 0x53.toByte(),
                0x32.toByte(), 0x38.toByte(), 0x53.toByte(), 0x50.toByte(), 0x42.toByte(), 0x42.toByte(), 0x31.toByte(),
                0x3b.toByte(), 0x75.toByte(), 0x73.toByte(), 0x65.toByte(), 0x72.toByte(), 0x3d.toByte(), 0x70.toByte(),
                0x68.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x65.toByte(), 0x3e.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x43.toByte(), 0x61.toByte(), 0x6c.toByte(), 0x6c.toByte(), 0x2d.toByte(), 0x49.toByte(), 0x44.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x30.toByte(), 0x33.toByte(), 0x46.toByte(), 0x34.toByte(), 0x31.toByte(),
                0x41.toByte(), 0x43.toByte(), 0x43.toByte(), 0x41.toByte(), 0x36.toByte(), 0x43.toByte(), 0x32.toByte(),
                0x31.toByte(), 0x37.toByte(), 0x35.toByte(), 0x45.toByte(), 0x36.toByte(), 0x38.toByte(), 0x46.toByte(),
                0x36.toByte(), 0x37.toByte(), 0x44.toByte(), 0x39.toByte(), 0x37.toByte(), 0x40.toByte(), 0x30.toByte(),
                0x64.toByte(), 0x37.toByte(), 0x30.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(),
                0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x66.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(),
                0x53.toByte(), 0x65.toByte(), 0x71.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x31.toByte(), 0x20.toByte(),
                0x49.toByte(), 0x4e.toByte(), 0x56.toByte(), 0x49.toByte(), 0x54.toByte(), 0x45.toByte(), 0x0d.toByte(),
                0x0a.toByte(), 0x4d.toByte(), 0x61.toByte(), 0x78.toByte(), 0x2d.toByte(), 0x46.toByte(), 0x6f.toByte(),
                0x72.toByte(), 0x77.toByte(), 0x61.toByte(), 0x72.toByte(), 0x64.toByte(), 0x73.toByte(), 0x3a.toByte(),
                0x20.toByte(), 0x36.toByte(), 0x37.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x52.toByte(), 0x65.toByte(),
                0x71.toByte(), 0x75.toByte(), 0x65.toByte(), 0x73.toByte(), 0x74.toByte(), 0x2d.toByte(), 0x44.toByte(),
                0x69.toByte(), 0x73.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x73.toByte(), 0x69.toByte(), 0x74.toByte(),
                0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x6e.toByte(), 0x6f.toByte(),
                0x2d.toByte(), 0x66.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x6b.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x61.toByte(), 0x63.toByte(), 0x74.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x3c.toByte(), 0x73.toByte(), 0x69.toByte(), 0x70.toByte(), 0x3a.toByte(),
                0x31.toByte(), 0x30.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x39.toByte(), 0x30.toByte(), 0x2e.toByte(),
                0x39.toByte(), 0x30.toByte(), 0x2e.toByte(), 0x38.toByte(), 0x33.toByte(), 0x3a.toByte(), 0x35.toByte(),
                0x30.toByte(), 0x36.toByte(), 0x30.toByte(), 0x3b.toByte(), 0x79.toByte(), 0x6f.toByte(), 0x70.toByte(),
                0x3d.toByte(), 0x30.toByte(), 0x30.toByte(), 0x2e.toByte(), 0x30.toByte(), 0x30.toByte(), 0x2e.toByte(),
                0x46.toByte(), 0x43.toByte(), 0x32.toByte(), 0x45.toByte(), 0x37.toByte(), 0x38.toByte(), 0x46.toByte(),
                0x35.toByte(), 0x2e.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x2e.toByte(),
                0x37.toByte(), 0x30.toByte(), 0x30.toByte(), 0x44.toByte(), 0x3b.toByte(), 0x74.toByte(), 0x72.toByte(),
                0x61.toByte(), 0x6e.toByte(), 0x73.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(),
                0x3d.toByte(), 0x75.toByte(), 0x64.toByte(), 0x70.toByte(), 0x3e.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x53.toByte(), 0x75.toByte(), 0x70.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x72.toByte(), 0x74.toByte(),
                0x65.toByte(), 0x64.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x31.toByte(), 0x30.toByte(), 0x30.toByte(),
                0x72.toByte(), 0x65.toByte(), 0x6c.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x41.toByte(), 0x6c.toByte(),
                0x6c.toByte(), 0x6f.toByte(), 0x77.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x41.toByte(), 0x43.toByte(),
                0x4b.toByte(), 0x2c.toByte(), 0x42.toByte(), 0x59.toByte(), 0x45.toByte(), 0x2c.toByte(), 0x43.toByte(),
                0x41.toByte(), 0x4e.toByte(), 0x43.toByte(), 0x45.toByte(), 0x4c.toByte(), 0x2c.toByte(), 0x49.toByte(),
                0x4e.toByte(), 0x46.toByte(), 0x4f.toByte(), 0x2c.toByte(), 0x49.toByte(), 0x4e.toByte(), 0x56.toByte(),
                0x49.toByte(), 0x54.toByte(), 0x45.toByte(), 0x2c.toByte(), 0x4e.toByte(), 0x4f.toByte(), 0x54.toByte(),
                0x49.toByte(), 0x46.toByte(), 0x59.toByte(), 0x2c.toByte(), 0x4f.toByte(), 0x50.toByte(), 0x54.toByte(),
                0x49.toByte(), 0x4f.toByte(), 0x4e.toByte(), 0x53.toByte(), 0x2c.toByte(), 0x50.toByte(), 0x52.toByte(),
                0x41.toByte(), 0x43.toByte(), 0x4b.toByte(), 0x2c.toByte(), 0x55.toByte(), 0x50.toByte(), 0x44.toByte(),
                0x41.toByte(), 0x54.toByte(), 0x45.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(), 0x6f.toByte(),
                0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x2d.toByte(), 0x54.toByte(),
                0x79.toByte(), 0x70.toByte(), 0x65.toByte(), 0x3a.toByte(), 0x20.toByte(), 0x6d.toByte(), 0x75.toByte(),
                0x6c.toByte(), 0x74.toByte(), 0x69.toByte(), 0x70.toByte(), 0x61.toByte(), 0x72.toByte(), 0x74.toByte(),
                0x2f.toByte(), 0x6d.toByte(), 0x69.toByte(), 0x78.toByte(), 0x65.toByte(), 0x64.toByte(), 0x3b.toByte(),
                0x62.toByte(), 0x6f.toByte(), 0x75.toByte(), 0x6e.toByte(), 0x64.toByte(), 0x61.toByte(), 0x72.toByte(),
                0x79.toByte(), 0x3d.toByte(), 0x38.toByte(), 0x34.toByte(), 0x41.toByte(), 0x36.toByte(), 0x33.toByte(),
                0x32.toByte(), 0x32.toByte(), 0x35.toByte(), 0x30.toByte(), 0x43.toByte(), 0x31.toByte(), 0x35.toByte(),
                0x37.toByte(), 0x39.toByte(), 0x39.toByte(), 0x38.toByte(), 0x46.toByte(), 0x34.toByte(), 0x42.toByte(),
                0x30.toByte(), 0x44.toByte(), 0x30.toByte(), 0x46.toByte(), 0x36.toByte(), 0x32.toByte(), 0x45.toByte(),
                0x45.toByte(), 0x45.toByte(), 0x38.toByte(), 0x37.toByte(), 0x35.toByte(), 0x38.toByte(), 0x41.toByte(),
                0x41.toByte(), 0x39.toByte(), 0x35.toByte(), 0x46.toByte(), 0x37.toByte(), 0x42.toByte(), 0x32.toByte(),
                0x0d.toByte(), 0x0a.toByte(), 0x4d.toByte(), 0x49.toByte(), 0x4d.toByte(), 0x45.toByte(), 0x2d.toByte(),
                0x56.toByte(), 0x65.toByte(), 0x72.toByte(), 0x73.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x3a.toByte(), 0x20.toByte(), 0x31.toByte(), 0x2e.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x0d.toByte(), 0x0a.toByte(), 0x2d.toByte(), 0x2d.toByte(), 0x38.toByte(), 0x34.toByte(), 0x41.toByte(),
                0x36.toByte(), 0x33.toByte(), 0x32.toByte(), 0x32.toByte(), 0x35.toByte(), 0x30.toByte(), 0x43.toByte(),
                0x31.toByte(), 0x35.toByte(), 0x37.toByte(), 0x39.toByte(), 0x39.toByte(), 0x38.toByte(), 0x46.toByte(),
                0x34.toByte(), 0x42.toByte(), 0x30.toByte(), 0x44.toByte(), 0x30.toByte(), 0x46.toByte(), 0x36.toByte(),
                0x32.toByte(), 0x45.toByte(), 0x45.toByte(), 0x45.toByte(), 0x38.toByte(), 0x37.toByte(), 0x35.toByte(),
                0x38.toByte(), 0x41.toByte(), 0x41.toByte(), 0x39.toByte(), 0x35.toByte(), 0x46.toByte(), 0x37.toByte(),
                0x42.toByte(), 0x32.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x2d.toByte(), 0x54.toByte(), 0x79.toByte(),
                0x70.toByte(), 0x65.toByte(), 0x3a.toByte(), 0x61.toByte(), 0x70.toByte(), 0x70.toByte(), 0x6c.toByte(),
                0x69.toByte(), 0x63.toByte(), 0x61.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x2f.toByte(), 0x73.toByte(), 0x64.toByte(), 0x70.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(),
                0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x2d.toByte(),
                0x44.toByte(), 0x69.toByte(), 0x73.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x73.toByte(), 0x69.toByte(),
                0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x3a.toByte(), 0x73.toByte(), 0x65.toByte(),
                0x73.toByte(), 0x73.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x3b.toByte(), 0x68.toByte(),
                0x61.toByte(), 0x6e.toByte(), 0x64.toByte(), 0x6c.toByte(), 0x69.toByte(), 0x6e.toByte(), 0x67.toByte(),
                0x3d.toByte(), 0x72.toByte(), 0x65.toByte(), 0x71.toByte(), 0x75.toByte(), 0x69.toByte(), 0x72.toByte(),
                0x65.toByte(), 0x64.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x76.toByte(),
                0x3d.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x6f.toByte(), 0x3d.toByte(), 0x2d.toByte(),
                0x20.toByte(), 0x30.toByte(), 0x20.toByte(), 0x30.toByte(), 0x20.toByte(), 0x49.toByte(), 0x4e.toByte(),
                0x20.toByte(), 0x49.toByte(), 0x50.toByte(), 0x34.toByte(), 0x20.toByte(), 0x31.toByte(), 0x30.toByte(),
                0x2e.toByte(), 0x32.toByte(), 0x34.toByte(), 0x39.toByte(), 0x2e.toByte(), 0x35.toByte(), 0x30.toByte(),
                0x2e.toByte(), 0x31.toByte(), 0x39.toByte(), 0x34.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x73.toByte(),
                0x3d.toByte(), 0x2d.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x63.toByte(), 0x3d.toByte(), 0x49.toByte(),
                0x4e.toByte(), 0x20.toByte(), 0x49.toByte(), 0x50.toByte(), 0x34.toByte(), 0x20.toByte(), 0x31.toByte(),
                0x30.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x39.toByte(), 0x36.toByte(), 0x2e.toByte(), 0x31.toByte(),
                0x30.toByte(), 0x2e.toByte(), 0x31.toByte(), 0x39.toByte(), 0x37.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x74.toByte(), 0x3d.toByte(), 0x30.toByte(), 0x20.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x6d.toByte(), 0x3d.toByte(), 0x61.toByte(), 0x75.toByte(), 0x64.toByte(), 0x69.toByte(), 0x6f.toByte(),
                0x20.toByte(), 0x33.toByte(), 0x35.toByte(), 0x33.toByte(), 0x34.toByte(), 0x34.toByte(), 0x20.toByte(),
                0x52.toByte(), 0x54.toByte(), 0x50.toByte(), 0x2f.toByte(), 0x41.toByte(), 0x56.toByte(), 0x50.toByte(),
                0x20.toByte(), 0x38.toByte(), 0x20.toByte(), 0x31.toByte(), 0x33.toByte(), 0x20.toByte(), 0x39.toByte(),
                0x36.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x62.toByte(), 0x3d.toByte(), 0x41.toByte(), 0x53.toByte(),
                0x3a.toByte(), 0x38.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x61.toByte(), 0x3d.toByte(),
                0x72.toByte(), 0x74.toByte(), 0x70.toByte(), 0x6d.toByte(), 0x61.toByte(), 0x70.toByte(), 0x3a.toByte(),
                0x38.toByte(), 0x20.toByte(), 0x50.toByte(), 0x43.toByte(), 0x4d.toByte(), 0x41.toByte(), 0x2f.toByte(),
                0x38.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x61.toByte(),
                0x3d.toByte(), 0x72.toByte(), 0x74.toByte(), 0x70.toByte(), 0x6d.toByte(), 0x61.toByte(), 0x70.toByte(),
                0x3a.toByte(), 0x31.toByte(), 0x33.toByte(), 0x20.toByte(), 0x43.toByte(), 0x4e.toByte(), 0x2f.toByte(),
                0x38.toByte(), 0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x61.toByte(),
                0x3d.toByte(), 0x72.toByte(), 0x74.toByte(), 0x70.toByte(), 0x6d.toByte(), 0x61.toByte(), 0x70.toByte(),
                0x3a.toByte(), 0x39.toByte(), 0x36.toByte(), 0x20.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6c.toByte(),
                0x65.toByte(), 0x70.toByte(), 0x68.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x65.toByte(), 0x2d.toByte(),
                0x65.toByte(), 0x76.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x2f.toByte(), 0x38.toByte(),
                0x30.toByte(), 0x30.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x61.toByte(), 0x3d.toByte(),
                0x70.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x3a.toByte(), 0x32.toByte(),
                0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x61.toByte(), 0x3d.toByte(), 0x6d.toByte(), 0x61.toByte(),
                0x78.toByte(), 0x70.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6d.toByte(), 0x65.toByte(), 0x3a.toByte(),
                0x32.toByte(), 0x30.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x2d.toByte(),
                0x2d.toByte(), 0x38.toByte(), 0x34.toByte(), 0x41.toByte(), 0x36.toByte(), 0x33.toByte(), 0x32.toByte(),
                0x32.toByte(), 0x35.toByte(), 0x30.toByte(), 0x43.toByte(), 0x31.toByte(), 0x35.toByte(), 0x37.toByte(),
                0x39.toByte(), 0x39.toByte(), 0x38.toByte(), 0x46.toByte(), 0x34.toByte(), 0x42.toByte(), 0x30.toByte(),
                0x44.toByte(), 0x30.toByte(), 0x46.toByte(), 0x36.toByte(), 0x32.toByte(), 0x45.toByte(), 0x45.toByte(),
                0x45.toByte(), 0x38.toByte(), 0x37.toByte(), 0x35.toByte(), 0x38.toByte(), 0x41.toByte(), 0x41.toByte(),
                0x39.toByte(), 0x35.toByte(), 0x46.toByte(), 0x37.toByte(), 0x42.toByte(), 0x32.toByte(), 0x0d.toByte(),
                0x0a.toByte(), 0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(),
                0x74.toByte(), 0x2d.toByte(), 0x54.toByte(), 0x79.toByte(), 0x70.toByte(), 0x65.toByte(), 0x3a.toByte(),
                0x61.toByte(), 0x70.toByte(), 0x70.toByte(), 0x6c.toByte(), 0x69.toByte(), 0x63.toByte(), 0x61.toByte(),
                0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x2f.toByte(), 0x49.toByte(), 0x53.toByte(),
                0x55.toByte(), 0x50.toByte(), 0x3b.toByte(), 0x62.toByte(), 0x61.toByte(), 0x73.toByte(), 0x65.toByte(),
                0x3d.toByte(), 0x69.toByte(), 0x74.toByte(), 0x75.toByte(), 0x2d.toByte(), 0x74.toByte(), 0x39.toByte(),
                0x32.toByte(), 0x2b.toByte(), 0x3b.toByte(), 0x76.toByte(), 0x65.toByte(), 0x72.toByte(), 0x73.toByte(),
                0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x3d.toByte(), 0x69.toByte(), 0x74.toByte(), 0x75.toByte(),
                0x2d.toByte(), 0x74.toByte(), 0x39.toByte(), 0x32.toByte(), 0x2b.toByte(), 0x0d.toByte(), 0x0a.toByte(),
                0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(),
                0x2d.toByte(), 0x44.toByte(), 0x69.toByte(), 0x73.toByte(), 0x70.toByte(), 0x6f.toByte(), 0x73.toByte(),
                0x69.toByte(), 0x74.toByte(), 0x69.toByte(), 0x6f.toByte(), 0x6e.toByte(), 0x3a.toByte(), 0x73.toByte(),
                0x69.toByte(), 0x67.toByte(), 0x6e.toByte(), 0x61.toByte(), 0x6c.toByte(), 0x3b.toByte(), 0x68.toByte(),
                0x61.toByte(), 0x6e.toByte(), 0x64.toByte(), 0x6c.toByte(), 0x69.toByte(), 0x6e.toByte(), 0x67.toByte(),
                0x3d.toByte(), 0x72.toByte(), 0x65.toByte(), 0x71.toByte(), 0x75.toByte(), 0x69.toByte(), 0x72.toByte(),
                0x65.toByte(), 0x64.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x43.toByte(), 0x6f.toByte(), 0x6e.toByte(),
                0x74.toByte(), 0x65.toByte(), 0x6e.toByte(), 0x74.toByte(), 0x2d.toByte(), 0x54.toByte(), 0x72.toByte(),
                0x61.toByte(), 0x6e.toByte(), 0x73.toByte(), 0x66.toByte(), 0x65.toByte(), 0x72.toByte(), 0x2d.toByte(),
                0x45.toByte(), 0x6e.toByte(), 0x63.toByte(), 0x6f.toByte(), 0x64.toByte(), 0x69.toByte(), 0x6e.toByte(),
                0x67.toByte(), 0x3a.toByte(), 0x62.toByte(), 0x69.toByte(), 0x6e.toByte(), 0x61.toByte(), 0x72.toByte(),
                0x79.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x0d.toByte(), 0x0a.toByte(), 0x01.toByte(), 0x10.toByte(),
                0x20.toByte(), 0x01.toByte(), 0x0a.toByte(), 0x00.toByte(), 0x02.toByte(), 0x0a.toByte(), 0x08.toByte(),
                0x04.toByte(), 0x10.toByte(), 0x97.toByte(), 0x62.toByte(), 0x92.toByte(), 0x90.toByte(), 0x04.toByte(),
                0xf0.toByte(), 0x0a.toByte(), 0x08.toByte(), 0x84.toByte(), 0x11.toByte(), 0x97.toByte(), 0x11.toByte(),
                0x46.toByte(), 0x93.toByte(), 0x99.toByte(), 0x09.toByte(), 0x0b.toByte(), 0x07.toByte(), 0x03.toByte(),
                0x14.toByte(), 0x29.toByte(), 0x26.toByte(), 0x09.toByte(), 0x39.toByte(), 0x02.toByte(), 0x13.toByte(),
                0x02.toByte(), 0x03.toByte(), 0x32.toByte(), 0x28.toByte(), 0x07.toByte(), 0x03.toByte(), 0x10.toByte(),
                0x29.toByte(), 0x71.toByte(), 0x13.toByte(), 0x00.toByte(), 0x00.toByte(), 0x1d.toByte(), 0x03.toByte(),
                0x80.toByte(), 0x90.toByte(), 0xa3.toByte(), 0x31.toByte(), 0x02.toByte(), 0x00.toByte(), 0x5a.toByte(),
                0x6f.toByte(), 0x07.toByte(), 0x03.toByte(), 0x14.toByte(), 0x29.toByte(), 0x71.toByte(), 0x13.toByte(),
                0x00.toByte(), 0x00.toByte(), 0xc0.toByte(), 0x08.toByte(), 0x06.toByte(), 0x03.toByte(), 0x13.toByte(),
                0x19.toByte(), 0x61.toByte(), 0x34.toByte(), 0x99.toByte(), 0x99.toByte(), 0x39.toByte(), 0x06.toByte(),
                0x31.toByte(), 0xc0.toByte(), 0x6f.toByte(), 0xc0.toByte(), 0xc0.toByte(), 0xd0.toByte(), 0x00.toByte(),
                0x0d.toByte(), 0x0a.toByte(), 0x2d.toByte(), 0x2d.toByte(), 0x38.toByte(), 0x34.toByte(), 0x41.toByte(),
                0x36.toByte(), 0x33.toByte(), 0x32.toByte(), 0x32.toByte(), 0x35.toByte(), 0x30.toByte(), 0x43.toByte(),
                0x31.toByte(), 0x35.toByte(), 0x37.toByte(), 0x39.toByte(), 0x39.toByte(), 0x38.toByte(), 0x46.toByte(),
                0x34.toByte(), 0x42.toByte(), 0x30.toByte(), 0x44.toByte(), 0x30.toByte(), 0x46.toByte(), 0x36.toByte(),
                0x32.toByte(), 0x45.toByte(), 0x45.toByte(), 0x45.toByte(), 0x38.toByte(), 0x37.toByte(), 0x35.toByte(),
                0x38.toByte(), 0x41.toByte(), 0x41.toByte(), 0x39.toByte(), 0x35.toByte(), 0x46.toByte(), 0x37.toByte(),
                0x42.toByte(), 0x32.toByte(), 0x2d.toByte(), 0x2d.toByte()
        )
    }

    @Test
    fun `Parse single SIP message`() {
        val messages = SipMessageParser().parse(PACKET_1)
        assertEquals(1, messages.size)

        val message = messages[0]
        assertEquals("0211070C568140000EEA01FB@SFESIP1-id2-ext", message.callId())
        assertEquals(0, message.contentLengthHeader.contentLength)
    }

    @Test
    fun `Parse multiple SIP messages`() {
        val messages = SipMessageParser().parse(PACKET_2)
        assertEquals(2, messages.size)

        val message0 = messages[0]
        assertEquals("0211070C568140000EEA01FB@SFESIP1-id2-ext", message0.callId())
        assertEquals(0, message0.contentLengthHeader.contentLength)

        val message1 = messages[1]
        assertEquals("03F41ACCA6C2175E68F67D97@0d70ffffffff", message1.callId())
        assertEquals(660, message1.contentLengthHeader.contentLength)
    }
}
