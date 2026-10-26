package de.movedigital.oscdb.shared.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.time.format.DateTimeParseException

/**
 * Turns every error into an RFC 9457 `application/problem+json` response. Spring MVC's own exceptions
 * (unknown path, 405, 406, ...) are handled by [ResponseEntityExceptionHandler]; anything unexpected is a
 * generic 500 that does not leak exception details.
 */
@RestControllerAdvice
class ProblemHandling : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(ProblemHandling::class.java)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException): ProblemDetail = problem(HttpStatus.NOT_FOUND, e.detail)

    @ExceptionHandler(BadRequestException::class)
    fun badRequest(e: BadRequestException): ProblemDetail = problem(HttpStatus.BAD_REQUEST, e.detail)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(e: MethodArgumentTypeMismatchException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid value for parameter '${e.name}'")

    @ExceptionHandler(DateTimeParseException::class)
    fun badDate(e: DateTimeParseException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid date/time value '${e.parsedString}'")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        log.error("Unexpected error", e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred")
    }

    private fun problem(status: HttpStatus, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).also { it.title = status.reasonPhrase }
}
