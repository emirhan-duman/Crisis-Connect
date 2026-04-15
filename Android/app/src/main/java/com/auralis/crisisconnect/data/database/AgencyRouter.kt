package com.auralis.crisisconnect.data.database

import java.util.Locale

object AgencyRouter {
    private val countryAgencyMap = mapOf(
        "TR" to "AFAD",
        "US" to "FEMA",
        "IN" to "NDMA",
        "JP" to "JMA"
    )

    private val emailDomainAgencyMap = mapOf(
        "afad.gov.tr" to "AFAD",
        "fema.dhs.gov" to "FEMA",
        "dhs.gov" to "FEMA",
        "ndma.gov.in" to "NDMA",
        "jma.go.jp" to "JMA"
    )

    fun getAgencyForCountry(countryCode: String): String {
        val normalizedCountry = countryCode.trim().uppercase(Locale.US)
        return countryAgencyMap[normalizedCountry] ?: "International"
    }

    fun getAgencyForEmail(email: String): String? {
        val domain = email.substringAfter('@', missingDelimiterValue = "")
            .trim()
            .lowercase(Locale.US)
            .ifBlank { return null }

        val exactMatch = emailDomainAgencyMap[domain]
        if (!exactMatch.isNullOrBlank()) {
            return exactMatch
        }

        return emailDomainAgencyMap.entries.firstOrNull { (knownDomain, _) ->
            domain.endsWith(".$knownDomain")
        }?.value
    }
}
