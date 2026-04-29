package com.example.ordertracking.bdd;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class OrderTrackingStepDefinitions {

    private final TestRestTemplate restTemplate;
    private final InMemoryOrderTrackingTestConfig.InMemoryOrderStore orderStore;
    private final InMemoryOrderTrackingTestConfig.RecordingOrderEventPublisher eventPublisher;

    private ResponseEntity<Map<String, Object>> response;
    private ResponseEntity<List<Map<String, Object>>> listResponse;

    @Autowired
    public OrderTrackingStepDefinitions(
            TestRestTemplate restTemplate,
            InMemoryOrderTrackingTestConfig.InMemoryOrderStore orderStore,
            InMemoryOrderTrackingTestConfig.RecordingOrderEventPublisher eventPublisher
    ) {
        this.restTemplate = restTemplate;
        this.orderStore = orderStore;
        this.eventPublisher = eventPublisher;
    }

    @Before
    public void resetState() {
        orderStore.clear();
        eventPublisher.clear();
        response = null;
        listResponse = null;
    }

    @Given("an existing order:")
    public void anExistingOrder(DataTable table) {
        Map<String, String> order = table.asMaps().getFirst();
        registerOrder(order.get("orderId"), order.get("customerId"));
    }

    @Given("order {string} has status {string}")
    public void orderHasStatus(String orderId, String status) {
        switch (status) {
            case "CREATED" -> {
            }
            case "PACKED" -> updateOrder(orderId, "PACKED", "packed");
            case "SHIPPED" -> {
                updateOrder(orderId, "PACKED", "packed");
                updateOrder(orderId, "SHIPPED", "shipped");
            }
            case "DELIVERED" -> {
                updateOrder(orderId, "PACKED", "packed");
                updateOrder(orderId, "SHIPPED", "shipped");
                updateOrder(orderId, "DELIVERED", "delivered");
            }
            case "CANCELLED" -> updateOrder(orderId, "CANCELLED", "cancelled");
            default -> throw new IllegalArgumentException("Unsupported test status: " + status);
        }
    }

    @When("I register order {string} for customer {string}")
    public void iRegisterOrderForCustomer(String orderId, String customerId) {
        response = registerOrder(orderId, customerId);
    }

    @When("I track order {string}")
    public void iTrackOrder(String orderId) {
        response = exchange(HttpMethod.GET, "/api/orders/" + orderId, null);
    }

    @When("I update order {string} to status {string} with note {string}")
    public void iUpdateOrderToStatusWithNote(String orderId, String status, String note) {
        response = updateOrder(orderId, status, note);
    }

    @When("I list orders filtered by status {string}")
    public void iListOrdersFilteredByStatus(String status) {
        HttpEntity<Void> entity = new HttpEntity<>(authHeaders());
        listResponse = restTemplate.exchange(
                "/api/orders?status={status}",
                HttpMethod.GET,
                entity,
                new ParameterizedTypeReference<>() {
                },
                status
        );
    }

    @When("I track order {string} without credentials")
    public void iTrackOrderWithoutCredentials(String orderId) {
        response = restTemplate.exchange(
                "/api/orders/" + orderId,
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    @Then("the response status is {int}")
    public void theResponseStatusIs(int status) {
        HttpStatus expected = HttpStatus.valueOf(status);
        ResponseEntity<?> actual = listResponse != null ? listResponse : response;

        assertThat(actual).isNotNull();
        assertThat(actual.getStatusCode()).isEqualTo(expected);
    }

    @Then("the response contains order {string} with status {string}")
    public void theResponseContainsOrderWithStatus(String orderId, String status) {
        assertThat(response.getBody())
                .containsEntry("id", orderId)
                .containsEntry("status", status);
    }

    @Then("the response contains customer {string}")
    public void theResponseContainsCustomer(String customerId) {
        assertThat(response.getBody()).containsEntry("customerId", customerId);
    }

    @Then("the order history contains status {string} with note {string}")
    public void theOrderHistoryContainsStatusWithNote(String status, String note) {
        assertThat(history()).anySatisfy(event -> assertThat(event)
                .containsEntry("status", status)
                .containsEntry("note", note));
    }

    @Then("the listed orders are:")
    public void theListedOrdersAre(DataTable table) {
        List<Map<String, String>> expectedRows = table.asMaps();

        assertThat(listResponse.getBody()).hasSize(expectedRows.size());
        for (Map<String, String> expected : expectedRows) {
            assertThat(listResponse.getBody()).anySatisfy(order -> assertThat(order)
                    .containsEntry("id", expected.get("orderId"))
                    .containsEntry("customerId", expected.get("customerId"))
                    .containsEntry("status", expected.get("status")));
        }
    }

    @Then("the problem title is {string}")
    public void theProblemTitleIs(String title) {
        assertThat(response.getBody()).containsEntry("title", title);
    }

    @Then("the problem detail contains {string}")
    public void theProblemDetailContains(String detail) {
        assertThat((String) response.getBody().get("detail")).contains(detail);
    }

    @Then("a status change event was published")
    public void aStatusChangeEventWasPublished() {
        assertThat(eventPublisher.publishedCount()).isPositive();
    }

    private ResponseEntity<Map<String, Object>> registerOrder(String orderId, String customerId) {
        return exchange(HttpMethod.POST, "/api/orders", Map.of(
                "orderId", orderId,
                "customerId", customerId
        ));
    }

    private ResponseEntity<Map<String, Object>> updateOrder(String orderId, String status, String note) {
        return exchange(HttpMethod.PUT, "/api/orders/" + orderId + "/status", Map.of(
                "status", status,
                "note", note
        ));
    }

    private ResponseEntity<Map<String, Object>> exchange(HttpMethod method, String path, Object body) {
        HttpEntity<Object> entity = new HttpEntity<>(body, authHeaders());
        return restTemplate.exchange(
                path,
                method,
                entity,
                new ParameterizedTypeReference<>() {
                }
        );
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("api-user", "change-me");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> history() {
        return (List<Map<String, Object>>) response.getBody().get("history");
    }
}
