package kr.bojohelp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BojoHelpApplication {
    public static void main(String[] args) {
        SpringApplication.run(BojoHelpApplication.class, args);
    }
}
