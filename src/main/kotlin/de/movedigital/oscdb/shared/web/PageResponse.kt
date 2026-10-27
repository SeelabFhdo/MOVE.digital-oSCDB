package de.movedigital.oscdb.shared.web

data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

data class PageRequest(val page: Int, val size: Int) {
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_SIZE = 50
        const val MAX_SIZE = 500

        fun of(page: Int?, size: Int?): PageRequest {
            val p = page ?: 0
            val s = size ?: DEFAULT_SIZE
            if (p < 0) throw BadRequestException("page must be >= 0")
            if (s < 1 || s > MAX_SIZE) throw BadRequestException("size must be between 1 and $MAX_SIZE")
            return PageRequest(p, s)
        }
    }
}

class NotFoundException(val detail: String) : RuntimeException(detail)

class BadRequestException(val detail: String) : RuntimeException(detail)

class ConflictException(val detail: String) : RuntimeException(detail)
