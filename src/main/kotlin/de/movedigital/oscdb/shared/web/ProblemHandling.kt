package de.movedigital.oscdb.shared.web

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.format.DateTimeParseException

@RestControllerAdvice
class ProblemHandling {
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

    private fun problem(status: HttpStatus, detail: String): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).also { it.title = status.reasonPhrase }
}
