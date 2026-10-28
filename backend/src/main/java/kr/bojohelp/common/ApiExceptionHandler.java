package kr.bojohelp.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/** 오류는 RFC 7807(ProblemDetail). 예외 메시지·스택에 개인정보를 넣지 않는다. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail tooLarge() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "사진이 너무 커요. 10MB 이하로 올려 주세요.");
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail status(ResponseStatusException e) {
        var status = HttpStatus.valueOf(e.getStatusCode().value());
        return ProblemDetail.forStatusAndDetail(status, e.getReason() != null ? e.getReason() : status.getReasonPhrase());
    }
}
