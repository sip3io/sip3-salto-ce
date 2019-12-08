package io.sip3.salto.ce.util

import io.vertx.core.eventbus.EventBus
import io.vertx.core.eventbus.impl.EventBusImpl

fun EventBus.consumes(address: String): Boolean {
    return (this as? EventBusImpl)?.let {
        // Read protected `handlerMap` value
        val field = EventBusImpl::class.java
                .getDeclaredField("handlerMap")
        field.isAccessible = true
        val handlerMap = field.get(this) as? Map<String, Any>

        return@let handlerMap?.containsKey(address)
    } ?: false
}