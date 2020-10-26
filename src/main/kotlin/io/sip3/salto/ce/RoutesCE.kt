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

    // Attributes
    val attributes get() = "attributes"

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
    val sdp_info get() = "sdp_info"
    val sdp_session get() = "sdp_session"

    // Media
    // TODO: Do we really need `_raw` here? Maybe we can do it in analogy with `sip_tranasction`?
    //       Otherwise, I think it will be better to swap `rtcp` and `rtcp_raw`...
    val rtcp get() = "rtcp"
    val rtcp_raw get() = "rtcp_raw"
    val rtpr get() = "rtpr"
    val rtpr_raw get() = "rtpr_raw"

    // Mongo
    val mongo_bulk_writer get() = "mongo_bulk_writer"
}