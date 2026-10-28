package kr.bojohelp.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 오류는 RFC 7807(ProblemDetail). 예외 메시지·스택에 개인정보를 넣지 않는다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException e) {
        var status = HttpStatus.valueOf(e.getStatusCode().value());
        return ProblemDetail.forStatusAndDetail(status, status.getReasonPhrase());
    }
}
