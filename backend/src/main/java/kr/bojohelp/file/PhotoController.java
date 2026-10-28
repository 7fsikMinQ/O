package kr.bojohelp.file;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** 프로필 사진 업로드·목록·삭제(ACTOR 전용)와 서명 URL 로 사진 내려받기. */
@RestController
public class PhotoController {

    private final PhotoService photos;
    private final SignedUrls urls;

    public PhotoController(PhotoService photos, SignedUrls urls) {
        this.photos = photos;
        this.urls = urls;
    }

    @PostMapping("/me/actor-profile/photos")
    ResponseEntity<Map<String, Object>> upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kind", defaultValue = "PROFILE") String kind) throws IOException {
        PhotoService.Kind k;
        try {
            k = PhotoService.Kind.valueOf(kind);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(photos.upload(Long.parseLong(jwt.getSubject()), k, file.getBytes(), file.getOriginalFilename()));
        } catch (ImageSanitizer.InvalidImageException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/me/actor-profile/photos")
    List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt) {
        return photos.list(Long.parseLong(jwt.getSubject()));
    }

    @DeleteMapping("/me/actor-profile/photos/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        photos.delete(Long.parseLong(jwt.getSubject()), id);
        return ResponseEntity.noContent().build();
    }

    /** 서명 URL 로만 열린다(만료·변조 시 403). 사진은 비공개라 캐시를 허용하지 않는다. */
    @GetMapping("/files/{publicId}")
    ResponseEntity<byte[]> download(@PathVariable UUID publicId, @RequestParam long exp, @RequestParam String sig) {
        if (!urls.isValid(publicId, exp, sig)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(photos.read(publicId));
    }
}
