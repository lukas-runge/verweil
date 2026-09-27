package de.lukasrunge.verweil.core

import io.ktor.client.HttpClient

/** An HTTP client with the platform's native engine. */
expect fun platformHttpClient(): HttpClient
