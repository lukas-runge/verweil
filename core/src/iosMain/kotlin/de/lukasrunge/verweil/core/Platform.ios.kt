package de.lukasrunge.verweil.core

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import de.lukasrunge.verweil.core.db.VerweilDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpClient(): HttpClient = HttpClient(Darwin)

fun createDatabase(): VerweilDatabase = VerweilDatabase(NativeSqliteDriver(VerweilDatabase.Schema, "verweil.db"))
