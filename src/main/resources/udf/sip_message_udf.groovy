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

package udf

/*
    Default `sip_message_udf` endpoint
 */
vertx.eventBus().localConsumer("sip_message_udf", { event ->
    // Please, check `sip3-documentation` to learn what could be done within SIP3 UDFs.
    //
    // Example 1: User-Defined attribute
    // def packet = event.body()
    // def sip_message = packet['payload']
    // if (sip_message['from'].matches('<sip:100@.*')) {
    //     packet['attributes']['robocall'] = true
    // }
    //
    // Example 2: Service attribute
    // def packet = event.body()
    // def sip_message = packet['payload']
    // def from_header = sip_message['from']
    // def matcher = (from_header =~ /1(\d*)/)
    // if (matcher) {
    //     packet['attributes']['caller'] = matcher[0][1]
    // }
    event.reply(true)
})