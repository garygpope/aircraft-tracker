package com.codingpitch.aircrafttracker

import com.codingpitch.aircrafttracker.config.ApplicationServices
import com.codingpitch.aircrafttracker.http.aircraftRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

fun main(args: Array<String>) = io.ktor.server.netty.EngineMain.main(args)

@Suppress("unused") // Loaded by application.conf.
fun Application.serviceModule() {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            this@serviceModule.log.error("Aircraft feed unavailable", cause)
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Aircraft feed unavailable"))
        }
    }
    val services = ApplicationServices(environment.config)
    environment.monitor.subscribe(ApplicationStopped) { services.close() }
    routing {
        aircraftRoutes(services.aircraft)
        staticResources("/", "display", index = "index.html")
    }
}
