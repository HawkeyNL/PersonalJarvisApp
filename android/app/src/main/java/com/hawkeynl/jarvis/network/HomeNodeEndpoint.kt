package com.hawkeynl.jarvis.network

import com.hawkeynl.jarvis.BuildConfig
import java.net.URI

@JvmInline
value class HomeNodeEndpoint private constructor(val baseUrl: String) {
    companion object {
        fun parse(raw: String, allowInsecureLocal: Boolean = BuildConfig.DEBUG): EndpointValidation {
            val input = raw.trim().trimEnd('/')
            if (input.isEmpty()) return EndpointValidation.Invalid("Enter your Home Node address.")

            val uri = runCatching { URI(input) }.getOrNull()
                ?: return EndpointValidation.Invalid("This is not a valid web address.")
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") {
                return EndpointValidation.Invalid("Use https://, or http:// for a local network.")
            }
            val host = uri.host
                ?: return EndpointValidation.Invalid("The Home Node address is missing a hostname.")
            if (host.isBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
                return EndpointValidation.Invalid("Use only a Home Node address, without credentials, query or fragment.")
            }
            if (uri.path.orEmpty().let { it.isNotEmpty() && it != "/" }) {
                return EndpointValidation.Invalid("The Home Node address must not contain a path.")
            }
            if (scheme == "http" && (!allowInsecureLocal || !isLocalHost(host))) {
                return EndpointValidation.Invalid("HTTP is only allowed for local Home Nodes in a debug build.")
            }
            val displayHost = if (host.contains(':')) "[$host]" else host
            val authority = if (uri.port == -1) displayHost else "$displayHost:${uri.port}"
            return EndpointValidation.Valid(HomeNodeEndpoint("$scheme://$authority"))
        }

        private fun isLocalHost(host: String): Boolean {
            val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
            if (normalized == "localhost" || normalized == "::1" || normalized.endsWith(".local")) return true
            if (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")) return true
            val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                octets[0] == 127 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31)
        }
    }
}

sealed interface EndpointValidation {
    data class Valid(val endpoint: HomeNodeEndpoint) : EndpointValidation
    data class Invalid(val message: String) : EndpointValidation
}
