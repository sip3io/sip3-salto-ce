/*
 * Copyright 2018-2019 SIP3.IO, Inc.
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

vertx.eventBus().localConsumer("sip_message_udf", { event ->
    def packet = event.body()

    def attributes = packet['attributes']
    attributes['string'] = 'string'
    attributes['number'] = 42
    attributes['boolean'] = true
    attributes['list'] = [1, 2, 3, 4]

    event.reply(true)
})