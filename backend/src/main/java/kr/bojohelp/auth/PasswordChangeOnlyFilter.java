package kr.bojohelp.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** 임시 비밀번호로 로그인한 제한 토큰(pwd_change)은 비밀번호 변경과 로그아웃 외의 모든 요청을 403 으로 막는다. */
public class PasswordChangeOnlyFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && Boolean.TRUE.equals(jwt.getToken().getClaim("pwd_change"))) {
            String path = request.getRequestURI();
            boolean allowed = ("PUT".equals(request.getMethod()) && "/me/password".equals(path)) || ("POST".equals(request.getMethod()) && "/auth/logout".equals(path));
            if (!allowed) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/problem+json;charset=UTF-8");
                response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"임시 비밀번호로 로그인했어요. 먼저 새 비밀번호로 바꿔 주세요.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
