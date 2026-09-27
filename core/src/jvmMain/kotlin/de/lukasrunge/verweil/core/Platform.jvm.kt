package de.lukasrunge.verweil.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java

actual fun platformHttpClient(): HttpClient = HttpClient(Java)
