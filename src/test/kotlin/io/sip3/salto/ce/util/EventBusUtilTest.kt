package io.sip3.salto.ce.util

import io.sip3.commons.vertx.test.VertxTest
import org.junit.jupiter.api.Test

class EventBusUtilTest : VertxTest() {

    @Test
    fun `Define and test event bus consumer`() {
        runTest(
                deploy = {
                    vertx.eventBus().localConsumer<String>("test") {}
                },
                assert = {
                    vertx.setPeriodic(100) {
                        if (vertx.eventBus().consumes("test")) {
                            context.completeNow()
                        }
                    }
                }
        )
    }
}