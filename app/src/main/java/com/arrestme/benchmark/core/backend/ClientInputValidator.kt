package com.arrestme.benchmark.core.backend

/**
 * Client-side input validation matching OJJOTx/ArrestMe-backend Fastify Zod schemas
 * (`src/modules/auth/auth.schemas.ts`).
 */
object ClientInputValidator {
    private val USERNAME_REGEX = Regex("^[a-z0-9_]+$")
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val HAS_LETTER_REGEX = Regex("[A-Za-z]")
    private val HAS_NUMBER_OR_SYMBOL_REGEX = Regex("[0-9\\W_]")

    fun validateUsername(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.length < 3) return "Username must be at least 3 characters."
        if (trimmed.length > 32) return "Username must be at most 32 characters."
        if (!USERNAME_REGEX.matches(trimmed)) {
            return "Username may only contain letters, numbers, and underscores."
        }
        return null
    }

    fun validateEmail(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Email is required."
        if (trimmed.length > 255) return "Email must not exceed 255 characters."
        if (!EMAIL_REGEX.matches(trimmed)) return "Enter a valid email address."
        return null
    }

    fun validateRegistrationPassword(password: String): String? {
        if (password.length < 10) {
            return "Password must be at least 10 characters."
        }
        if (password.length > 128) {
            return "Password must not exceed 128 characters."
        }
        if (!HAS_LETTER_REGEX.containsMatchIn(password) ||
            !HAS_NUMBER_OR_SYMBOL_REGEX.containsMatchIn(password)
        ) {
            return "Password must contain at least one letter and one number or symbol."
        }
        return null
    }

    fun validateLoginPassword(password: String): String? {
        if (password.isEmpty()) return "Password is required."
        if (password.length > 128) return "Password must not exceed 128 characters."
        return null
    }

    fun validateDisplayName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        if (raw.trim().length > 80) return "Display name must not exceed 80 characters."
        return null
    }
}
