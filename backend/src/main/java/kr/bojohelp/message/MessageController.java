package kr.bojohelp.message;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import kr.bojohelp.company.CompanyContext;
import kr.bojohelp.company.Roles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 메시지·공지·알림함·⭐ 출연자·차단·신고(API-OP04~05, OP14~17, OP32~36, ME16~18, NT01~04, AU16, BD09). */
@RestController
public class MessageController {

    public record BodyReq(@NotBlank @Size(max = 2000) String body) {}

    public record StarReq(@Size(max = 200) String note) {}

    public record StarAlertReq(@NotNull Boolean enabled) {}

    public record ReportReq(@NotBlank @Pattern(regexp = "POST|COMMENT|JOB_POSTING|USER|COMPANY") String targetType, @NotNull Long targetId, @NotBlank @Size(max = 30) String reasonCode, @Size(max = 1000) String detail) {}

    private final MessageService messages;
    private final NotificationService notifications;
    private final CompanyContext companies;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public MessageController(MessageService messages, NotificationService notifications, CompanyContext companies, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.messages = messages;
        this.notifications = notifications;
        this.companies = companies;
        this.jdbc = jdbc;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    private long companyOfBroadcast(long id) {
        return jdbc.sql("SELECT company_id FROM job_broadcasts WHERE id = :id").param("id", id).query(Long.class).optional().orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    // ----- 업체 -----

    @PostMapping("/company/messages")
    ResponseEntity<Map<String, Object>> start(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId, @Valid @RequestBody MessageService.StartReq req) {
        var m = companies.resolve(uid(jwt), companyId, Roles.FIELD);
        return ResponseEntity.status(HttpStatus.CREATED).body(messages.start(uid(jwt), m.companyId(), req));
    }

    @GetMapping("/company/messages/threads")
    List<Map<String, Object>> companyThreads(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId) {
        return messages.companyThreads(companies.resolve(uid(jwt), companyId, Roles.FIELD).companyId());
    }

    @GetMapping("/company/messages/threads/{id}")
    Map<String, Object> companyThread(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam(required = false) Long companyId) {
        return messages.companyThread(companies.resolve(uid(jwt), companyId, Roles.FIELD).companyId(), id, true);
    }

    @PostMapping("/company/messages/threads/{id}/reply")
    Map<String, Object> companyReply(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam(required = false) Long companyId, @Valid @RequestBody BodyReq req) {
        return messages.companyReply(uid(jwt), companies.resolve(uid(jwt), companyId, Roles.FIELD).companyId(), id, req.body());
    }

    @PostMapping("/company/broadcasts")
    ResponseEntity<Map<String, Object>> broadcast(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId, @Valid @RequestBody MessageService.BroadcastReq req) {
        var m = companies.resolve(uid(jwt), companyId, Roles.FIELD);
        return ResponseEntity.status(HttpStatus.CREATED).body(messages.broadcast(uid(jwt), m.companyId(), req));
    }

    @GetMapping("/company/broadcasts")
    List<Map<String, Object>> broadcasts(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId) {
        return messages.broadcasts(companies.resolve(uid(jwt), companyId, Roles.FIELD).companyId());
    }

    @GetMapping("/company/broadcasts/{id}/receipts")
    Map<String, Object> receipts(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        long cid = companyOfBroadcast(id);
        companies.require(uid(jwt), cid, Roles.MANAGERS);
        return messages.receipts(cid, id);
    }

    @PostMapping("/company/broadcasts/{id}/remind-unread")
    Map<String, Object> remind(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        long cid = companyOfBroadcast(id);
        companies.require(uid(jwt), cid, Roles.MANAGERS);
        return messages.remindUnread(uid(jwt), cid, id);
    }

    @GetMapping("/company/starred-actors")
    List<Map<String, Object>> stars(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long companyId) {
        return messages.stars(companies.resolve(uid(jwt), companyId, Roles.MANAGERS).companyId());
    }

    @PutMapping("/company/starred-actors/{actorUserId}")
    Map<String, Object> star(@AuthenticationPrincipal Jwt jwt, @PathVariable long actorUserId, @RequestParam(required = false) Long companyId, @RequestBody(required = false) StarReq req) {
        return messages.star(uid(jwt), companies.resolve(uid(jwt), companyId, Roles.MANAGERS).companyId(), actorUserId, req == null ? null : req.note());
    }

    @DeleteMapping("/company/starred-actors/{actorUserId}")
    ResponseEntity<Void> unstar(@AuthenticationPrincipal Jwt jwt, @PathVariable long actorUserId, @RequestParam(required = false) Long companyId) {
        messages.unstar(companies.resolve(uid(jwt), companyId, Roles.MANAGERS).companyId(), actorUserId);
        return ResponseEntity.noContent().build();
    }

    // ----- 출연자 -----

    @GetMapping("/me/messages/threads")
    List<Map<String, Object>> actorThreads(@AuthenticationPrincipal Jwt jwt) {
        return messages.actorThreads(uid(jwt));
    }

    @GetMapping("/me/messages/threads/{id}")
    Map<String, Object> actorThread(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return messages.actorThread(uid(jwt), id);
    }

    @PostMapping("/me/messages/threads/{id}/reply")
    Map<String, Object> actorReply(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody BodyReq req) {
        return messages.actorReply(uid(jwt), id, req.body());
    }

    @PutMapping("/me/blocks/companies/{companyId}")
    ResponseEntity<Void> block(@AuthenticationPrincipal Jwt jwt, @PathVariable long companyId) {
        messages.blockCompany(uid(jwt), companyId, true);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me/blocks/companies/{companyId}")
    ResponseEntity<Void> unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable long companyId) {
        messages.blockCompany(uid(jwt), companyId, false);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/settings/star-alerts")
    ResponseEntity<Void> starAlerts(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody StarAlertReq req) {
        messages.setStarAlerts(uid(jwt), req.enabled());
        return ResponseEntity.noContent().build();
    }

    // ----- 공통 -----

    @PostMapping("/reports")
    ResponseEntity<Map<String, Object>> report(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReportReq req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(messages.report(uid(jwt), req.targetType(), req.targetId(), req.reasonCode(), req.detail()));
    }

    @GetMapping("/notifications")
    List<Map<String, Object>> notificationList(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long before, @RequestParam(defaultValue = "30") int limit) {
        return notifications.list(uid(jwt), before, limit);
    }

    @GetMapping("/notifications/unread-count")
    Map<String, Object> unread(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", notifications.unreadCount(uid(jwt)));
    }

    @PatchMapping("/notifications/{id}/read")
    ResponseEntity<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        notifications.markRead(uid(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications/read-all")
    Map<String, Object> readAll(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("updated", notifications.markAllRead(uid(jwt)));
    }
}
