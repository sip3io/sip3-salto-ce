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

package io.sip3.salto.ce

import io.sip3.commons.Routes

interface RoutesCE : Routes {

    companion object : RoutesCE

    // Decoder
    val sip3 get() = "sip3"
    val hep2 get() = "hep2"
    val hep3 get() = "hep3"

    // Attributes
    val attributes get() = "attributes"

    // Router
    val router get() = "router"

    // RTPR
    val rtpr get() = "rtpr"

    // SDP
    val sdp_info get() = "sdp_info"
    val sdp_session get() = "sdp_session"

    // SIP
    val sip get() = "sip"
    val sip_message_udf get() = "sip_message_udf"

    // Mongo
    val mongo_bulk_writer get() = "mongo_bulk_writer"
}