package org.example.artiselite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class ArtiseliteApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArtiseliteApplication.class, args);
    }

}
