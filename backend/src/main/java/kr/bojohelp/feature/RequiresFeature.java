package kr.bojohelp.feature;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 컨트롤러 메서드·클래스에 붙이면 해당 기능 플래그가 꺼져 있을 때 404 를 돌려준다(존재를 드러내지 않음). 17번 문서. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresFeature {
    String value();
}
