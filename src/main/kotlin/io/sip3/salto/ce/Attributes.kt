/*
 * Copyright 2018-2021 SIP3.IO, Corp.
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

object Attributes {

    const val src_addr = "src_addr"
    const val src_host = "src_host"
    const val dst_addr = "dst_addr"
    const val dst_host = "dst_host"
    const val method = "method"
    const val call_id = "call_id"
    const val x_call_id = "x_call_id"
    const val state = "state"
    const val caller = "caller"
    const val callee = "callee"
    const val expired = "expired"
    const val error_code = "error_code"
    const val error_type = "error_type"
    const val duration = "duration"
    const val distribution = "distribution"
    const val trying_delay = "trying_delay"
    const val setup_time = "setup_time"
    const val establish_time = "establish_time"
    const val cancel_time = "cancel_time"
    const val disconnect_time = "disconnect_time"
    const val terminated_by = "terminated_by"
    const val transactions = "transactions"
    const val retransmits = "retransmits"
    const val mos = "mos"
    const val r_factor = "r_factor"
    const val ranked = "ranked"
    const val one_way = "one_way"
    const val undefined_codec = "undefined_codec"
    const val missed_peer = "missed_peer"
    const val bad_report_fraction = "bad_report_fraction"
    const val overlapped_interval = "overlapped_interval"
    const val overlapped_fraction = "overlapped_fraction"
    const val recording_mode = "recording_mode"
}