package kr.bojohelp.feature;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class FeatureGateInterceptor implements HandlerInterceptor {

    private final FeatureFlagService flags;

    public FeatureGateInterceptor(FeatureFlagService flags) {
        this.flags = flags;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod hm) {
            RequiresFeature gate = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RequiresFeature.class);
            if (gate == null) {
                gate = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequiresFeature.class);
            }
            if (gate != null && !flags.isEnabled(gate.value())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
        }
        return true;
    }
}
