package fr.wael.reactiveorders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // Spring gère la souscription du relay réactif, sans subscribe() caché dans un service.
public class ReactiveOrdersApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReactiveOrdersApplication.class, args);
    }
}
