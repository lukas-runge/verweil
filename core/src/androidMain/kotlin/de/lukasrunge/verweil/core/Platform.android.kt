package de.lukasrunge.verweil.core

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import de.lukasrunge.verweil.core.db.VerweilDatabase
import de.lukasrunge.verweil.core.upload.Outbox
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformHttpClient(): HttpClient = HttpClient(OkHttp)

fun createOutbox(context: Context): Outbox =
    Outbox(VerweilDatabase(AndroidSqliteDriver(VerweilDatabase.Schema, context, "verweil.db")))
