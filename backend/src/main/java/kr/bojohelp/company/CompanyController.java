package kr.bojohelp.company;

import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 업체 등록·프로필·직원·초대(로그인한 사용자). 업체 데이터 접근은 CompanyAccess 가 구성원·직책으로 검사한다. */
@RestController
public class CompanyController {

    private final CompanyService companies;
    private final CompanyMemberService members;
    private final CompanyLicenseService licenses;

    public CompanyController(CompanyService companies, CompanyMemberService members, CompanyLicenseService licenses) {
        this.companies = companies;
        this.members = members;
        this.licenses = licenses;
    }

    private static long uid(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    @PostMapping("/companies")
    ResponseEntity<Map<String, Object>> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompanyService.RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(companies.register(uid(jwt), req));
    }

    @PostMapping("/companies/{id}/resubmit")
    Map<String, Object> resubmit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return companies.resubmit(uid(jwt), id);
    }

    @PostMapping("/companies/{id}/license")
    ResponseEntity<Map<String, Object>> license(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED).body(licenses.upload(uid(jwt), id, file.getBytes(), file.getOriginalFilename()));
    }

    @GetMapping("/me/companies")
    List<Map<String, Object>> mine(@AuthenticationPrincipal Jwt jwt) {
        return companies.mine(uid(jwt));
    }

    @GetMapping("/companies/{id}")
    Map<String, Object> get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return companies.get(uid(jwt), id);
    }

    @PatchMapping("/companies/{id}")
    ResponseEntity<Void> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CompanyService.ProfilePatch req) {
        companies.patch(uid(jwt), id, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/companies/{id}/members")
    List<Map<String, Object>> members(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return members.list(uid(jwt), id);
    }

    @PostMapping("/companies/{id}/members")
    ResponseEntity<Void> invite(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody CompanyMemberService.InviteRequest req) {
        members.invite(uid(jwt), id, req);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PatchMapping("/companies/{id}/members/{userId}")
    ResponseEntity<Void> patchMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable long userId, @Valid @RequestBody CompanyMemberService.MemberPatch req) {
        members.patch(uid(jwt), id, userId, req);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/company-invitations")
    List<Map<String, Object>> invitations(@AuthenticationPrincipal Jwt jwt) {
        return members.myInvitations(uid(jwt));
    }

    @PostMapping("/me/company-invitations/{companyId}/accept")
    ResponseEntity<Void> accept(@AuthenticationPrincipal Jwt jwt, @PathVariable long companyId) {
        members.respond(uid(jwt), companyId, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/company-invitations/{companyId}/decline")
    ResponseEntity<Void> decline(@AuthenticationPrincipal Jwt jwt, @PathVariable long companyId) {
        members.respond(uid(jwt), companyId, false);
        return ResponseEntity.noContent().build();
    }
}
