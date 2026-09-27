package de.lukasrunge.verweil.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.upload.Outbox
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

fun createOutbox(): Outbox = Outbox(VerweilDatabase(NativeSqliteDriver(VerweilDatabase.Schema, "verweil.db")))
