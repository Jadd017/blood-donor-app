package com.jadbarakat.blooddonor.server

import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelPipeline
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Closes new connections beyond [max] open ones, so a flood of sockets can't exhaust the server. */
@ChannelHandler.Sharable
class ConnectionLimiter(private val max: Int) : ChannelInboundHandlerAdapter() {
    private val open = AtomicInteger()

    val openConnections: Int get() = open.get()

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (open.incrementAndGet() > max) {
            ctx.close()
        } else {
            ctx.fireChannelActive()
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        open.decrementAndGet()
        ctx.fireChannelInactive()
    }
}

/** Closes a connection that sent and received nothing for a while (e.g. an idle keep-alive or a slow client). */
private class CloseWhenIdle : ChannelInboundHandlerAdapter() {
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is IdleStateEvent) ctx.close() else ctx.fireUserEventTriggered(evt)
    }
}

/** Adds the connection cap and idle timeout in front of Ktor's own handlers. */
fun ChannelPipeline.addConnectionLimits(limiter: ConnectionLimiter, idleSeconds: Long) {
    addFirst("close-when-idle", CloseWhenIdle())
    addFirst("idle-state", IdleStateHandler(0, 0, idleSeconds, TimeUnit.SECONDS))
    addFirst("connection-limit", limiter)
}
