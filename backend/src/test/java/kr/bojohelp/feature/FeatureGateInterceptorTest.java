package kr.bojohelp.feature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

/** 꺼진 기능은 404(존재 비노출), 켜지면 통과, 어노테이션이 없으면 항상 통과 — DB 없이 확인. */
class FeatureGateInterceptorTest {

    static class Dummy {
        @RequiresFeature("BANK_TRANSFER_FILE")
        public void gated() {}

        public void open() {}
    }

    private final FeatureFlagService flags = mock(FeatureFlagService.class);
    private final FeatureGateInterceptor gate = new FeatureGateInterceptor(flags);

    private HandlerMethod handler(String name) throws Exception {
        return new HandlerMethod(new Dummy(), Dummy.class.getMethod(name));
    }

    @Test
    void offFeatureIs404() throws Exception {
        when(flags.isEnabled("BANK_TRANSFER_FILE")).thenReturn(false);
        assertThatThrownBy(() -> gate.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("gated")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));
    }

    @Test
    void onFeaturePasses() throws Exception {
        when(flags.isEnabled("BANK_TRANSFER_FILE")).thenReturn(true);
        assertThat(gate.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("gated"))).isTrue();
    }

    @Test
    void ungatedAlwaysPasses() throws Exception {
        assertThat(gate.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), handler("open"))).isTrue();
    }
}
