package com.drmohammed.antiporn.telemetry

/**
 * Result wrapper for DPM operations executed via [SafeDpmGateway].
 */
sealed class DpmExecutionResult<out T> {

    abstract val executionTimeMs: Long
    abstract val actionName: String

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Failure -> throw cause ?: RuntimeException("DPM Action [$actionName] failed ($errorType): $message")
    }

    data class Success<T>(
        val data: T,
        override val executionTimeMs: Long,
        override val actionName: String
    ) : DpmExecutionResult<T>()

    data class Failure(
        val errorType: DpmErrorType,
        val message: String,
        override val executionTimeMs: Long,
        override val actionName: String,
        val cause: Throwable? = null
    ) : DpmExecutionResult<Nothing>()
}

/**
 * Detailed error classification for DPM execution failures.
 */
enum class DpmErrorType {
    DPM_SERVICE_UNAVAILABLE,
    ADMIN_INACTIVE,
    NOT_DEVICE_OWNER,
    SECURITY_EXCEPTION,
    INVALID_ARGUMENT,
    ILLEGAL_STATE,
    EXECUTION_FAILED
}
