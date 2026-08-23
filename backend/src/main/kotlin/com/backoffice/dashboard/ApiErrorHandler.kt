package com.backoffice.dashboard

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * ResponseStatusException의 사유를 {"detail": "..."}로 내려준다.
 * Spring 기본 오류 본문에는 detail 필드가 없어서, 컨트롤러가 쓴 한글 안내 문구가
 * 화면까지 전달되지 않고 프런트의 일반 메시지로 대체되고 있었다.
 *
 * 여기서 노출하는 것은 우리가 직접 작성한 사유뿐이다. 그 외 예외는 처리하지 않으므로
 * 기본 500 응답으로 남고 내부 메시지가 새지 않는다.
 */
@RestControllerAdvice
class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun handle(error: ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.statusCode)
            .body(mapOf("detail" to (error.reason ?: "요청을 처리하지 못했습니다.")))
}
