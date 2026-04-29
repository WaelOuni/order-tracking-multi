package com.example.ordertracking.bdd;

import com.example.ordertracking.OrderTrackingApplication;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

@CucumberContextConfiguration
@SpringBootTest(
        classes = {OrderTrackingApplication.class, InMemoryOrderTrackingTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.user.name=api-user",
                "app.security.user.password=change-me",
                "spring.main.allow-bean-definition-overriding=true",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration"
        }
)
public class CucumberSpringConfiguration {
}
