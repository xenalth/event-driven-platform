package com.kmdev.hub

import com.kmdev.common.PlatformVerticle
import com.kmdev.common.genMessageId
import com.kmdev.common.moduleName
import io.reactivex.subjects.BehaviorSubject
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.reactivex.core.RxHelper
import io.vertx.reactivex.core.http.HttpServerResponse
import io.vertx.reactivex.ext.web.Router
import io.vertx.reactivex.ext.web.RoutingContext
import io.vertx.reactivex.ext.web.handler.BodyHandler
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

class ProxyVerticle : PlatformVerticle() {
    private val log = LoggerFactory.getLogger(ProxyVerticle::class.java.name)

    private val JSON_TYPE = "application/json"

    private val OUTBOUND = "/outbound"

    private val response = BehaviorSubject.create<String>()

    override fun start(future: Future<Void>) {
        val routes = createRouter()

        connectorName = moduleName("proxy")
        connect(OUTBOUND) { msg -> response.onNext(msg) }

        vertx.createHttpServer()
                .requestHandler { routes.accept(it) }
                .rxListen(8080, "localhost")
                .doOnSuccess { _ -> future.complete() }
                .doOnError { err -> future.fail(err) }
                .subscribe()
    }

    override fun stop(future: Future<Void>) {
        connector.disconnect()
        future.complete()
    }

    private fun createRouter(): Router {
        val router = Router.router(vertx)
        router.route().handler { BodyHandler.create() }
        router.route().failureHandler { handlerError }
        router.apply {
            get("/").handler(handlerIndex)
            get("/health").handler(handlerHealth).produces(JSON_TYPE)
            post("/event").handler(handlerEvent).produces(JSON_TYPE)
        }
        return router
    }

    val handlerError = Handler<RoutingContext> { req ->
        req.response().apply {
            statusCode = 500
            end("Ops! Something goes wrong.")
        }
    }

    val handlerIndex = Handler<RoutingContext> { req ->
        req.response().end("Proxy!")
    }

    val handlerEvent = Handler<RoutingContext> { req ->
        val data = req.bodyAsJson
        if (isInvalid(data)) {
            log.warn("Invalid payload data ${data.encode()}")
            notAcceptable(req.response())
            req.response().endWithJson(mapOf("message" to "Invalid event payload."))
            return@Handler
        }

        if (isInvalidType(data.getString("type"))) {
            log.warn("Invalid event type.")
            notAcceptable(req.response())
            req.response().endWithJson(mapOf("message" to "Invalid event type."))
            return@Handler
        }

        val topicName = "/${data.getString("type")}"
        if (!isCommand(data)) {
            connector.publish(topicName, data.encode())
            req.response().endWithJson(mapOf("message" to "Ok"))
            return@Handler
        }

        val messageId = genMessageId()
        connector.publish(topicName, data.put("id", messageId).encode())

        val reply = response.map { msg -> JsonObject(msg) }
                .timeout(2L, TimeUnit.SECONDS)
                .filter { json -> messageId.equals(json.getString("id"), true) }
                .subscribeOn(RxHelper.scheduler(vertx))
                .take(1)
                .singleOrError()

        reply.doOnError { err -> fail(req.response(), err.message) }
                .doOnSuccess { json -> req.response().endWithJson(json) }
                .subscribe()
    }

    val handlerHealth = Handler<RoutingContext> { req ->
        val dt = Date().toInstant().toString()
        req.response().endWithJson(mapOf("code" to "0", "message" to "OK", "date_time" to dt))
    }

    fun HttpServerResponse.endWithJson(obj: Any) {
        this.putHeader("Content-Type", JSON_TYPE).end(Json.encodePrettily(obj))
    }
}