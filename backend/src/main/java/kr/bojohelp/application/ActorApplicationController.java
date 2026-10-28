package kr.bojohelp.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import kr.bojohelp.auth.ClientIp;
import kr.bojohelp.file.ImageSanitizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 출연자의 지원·확정·취소·내 일정(API-AP01~AP10, ME19~20, OP27). ACTOR 역할 전용(SecurityConfig). */
@RestController
public class ActorApplicationController {

    public record ConfirmReq(Boolean acknowledgeConflict) {}

    public record CancelReq(String reason) {}

    private final ApplicationService applications;
    private final SelectionService selection;
    private final ApplyFileService files;

    public ActorApplicationController(ApplicationService applications, SelectionService selection, ApplyFileService files) {
        this.applications = applications;
        this.selection = selection;
        this.files = files;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @PostMapping("/me/apply-files")
    ResponseEntity<Map<String, Object>> upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file, @RequestParam String kind) throws IOException {
        if (!List.of("PHOTO", "SIGNATURE").contains(kind)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(files.upload(uid(jwt), kind, file.getBytes(), file.getOriginalFilename()));
        } catch (ImageSanitizer.InvalidImageException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/positions/{id}/apply-form")
    Map<String, Object> applyForm(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return applications.applyForm(uid(jwt), id);
    }

    @PostMapping("/positions/{id}/applications")
    ResponseEntity<Map<String, Object>> apply(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ApplicationService.ApplyReq req, HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(applications.apply(uid(jwt), id, req, ClientIp.of(http), http.getHeader("User-Agent")));
    }

    @DeleteMapping("/applications/{id}")
    Map<String, Object> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam(required = false) String reason) {
        return applications.cancel(uid(jwt), id, reason);
    }

    @PostMapping("/applications/{id}/confirm")
    Map<String, Object> confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody(required = false) ConfirmReq req) {
        return selection.confirm(uid(jwt), id, req != null && Boolean.TRUE.equals(req.acknowledgeConflict()));
    }

    @GetMapping("/applications/{id}/document")
    Map<String, Object> document(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return applications.document(uid(jwt), id);
    }

    @GetMapping("/me/applications")
    List<Map<String, Object>> mine(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String status) {
        return applications.mine(uid(jwt), status);
    }

    @GetMapping("/me/applications/{id}")
    Map<String, Object> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return applications.mineDetail(uid(jwt), id);
    }

    @GetMapping("/me/applications/{id}/offer")
    Map<String, Object> offer(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return applications.offerLetter(uid(jwt), id);
    }

    @GetMapping("/me/calendar")
    List<Map<String, Object>> calendar(@AuthenticationPrincipal Jwt jwt, @RequestParam String month) {
        return applications.myCalendar(uid(jwt), month);
    }

    @GetMapping("/me/conflicts")
    List<Map<String, Object>> conflicts(@AuthenticationPrincipal Jwt jwt) {
        return selection.myConflicts(uid(jwt));
    }

    @PostMapping("/me/conflicts/{id}/ack")
    ResponseEntity<Void> ack(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        selection.acknowledgeConflict(uid(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
