/*
 * Copyright 2018-2022 SIP3.IO, Corp.
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

package io.sip3.salto.ce

import io.sip3.commons.Routes

interface RoutesCE : Routes {

    companion object : RoutesCE

    // UDF
    val packet_udf get() = "packet_udf"
    val sip_message_udf get() = "sip_message_udf"
    val sip_call_udf get() = "sip_call_udf"

    // Decoder
    val sip3 get() = "sip3"
    val hep2 get() = "hep2"
    val hep3 get() = "hep3"

    // Router
    val router get() = "router"

    // SIP
    val sip get() = "sip"

    // SDP
    val sdp get() = "sdp"

    // Media
    val rtcp get() = "rtcp"
    val rtpr get() = "rtpr"
    val media get() = "media"

    // Recording
    val rec get() = "rec"

    // Mongo
    val mongo_bulk_writer get() = "mongo_bulk_writer"
}