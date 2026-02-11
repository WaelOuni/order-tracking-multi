package com.example.ordertracking.adapter.in.rest;

import com.example.ordertracking.domain.model.Order;
import com.example.ordertracking.application.port.in.RegisterOrderUseCase;
import com.example.ordertracking.application.port.in.TrackOrderUseCase;
import com.example.ordertracking.application.port.in.UpdateOrderStatusUseCase;
import com.example.ordertracking.application.port.in.ListOrdersUseCase;
import com.example.ordertracking.domain.model.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderTrackingController.class)
@Import({OrderRestMapper.class, com.example.ordertracking.config.SecurityConfig.class})
class OrderTrackingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RegisterOrderUseCase registerOrderUseCase;
    @MockitoBean
    private TrackOrderUseCase trackOrderUseCase;
    @MockitoBean
    private UpdateOrderStatusUseCase updateOrderStatusUseCase;
    @MockitoBean
    private ListOrdersUseCase listOrdersUseCase;

    @Test
    void shouldRegisterOrder() throws Exception {
        Order order = Order.create("o-1", "c-1");
        when(registerOrderUseCase.register(eq("o-1"), eq("c-1"))).thenReturn(order);

        mockMvc.perform(post("/api/orders")
                        .with(httpBasic("api-user", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"orderId":"o-1","customerId":"c-1"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("o-1"))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void shouldTrackOrder() throws Exception {
        Order order = Order.create("o-2", "c-2");
        when(trackOrderUseCase.getById("o-2")).thenReturn(order);

        mockMvc.perform(get("/api/orders/o-2")
                        .with(httpBasic("api-user", "change-me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("c-2"));
    }

    @Test
    void shouldUpdateStatus() throws Exception {
        Order order = Order.create("o-3", "c-3");
        order.transitionTo(OrderStatus.PACKED, order.updatedAt().plusSeconds(20), "packed");
        when(updateOrderStatusUseCase.updateStatus(eq("o-3"), eq(OrderStatus.PACKED), eq("packed"))).thenReturn(order);

        mockMvc.perform(put("/api/orders/o-3/status")
                        .with(httpBasic("api-user", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"status":"PACKED","note":"packed"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PACKED"));
    }
    @Test
    void shouldReturn404WhenOrderNotFound() throws Exception {
        when(trackOrderUseCase.getById("missing")).thenThrow(new IllegalArgumentException("Order not found: missing"));

        mockMvc.perform(get("/api/orders/missing")
                        .with(httpBasic("api-user", "change-me")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"));
    }

    @Test
    void shouldReturn409WhenTransitionInvalid() throws Exception {
        when(updateOrderStatusUseCase.updateStatus(eq("o-3"), eq(OrderStatus.DELIVERED), eq("invalid")))
                .thenThrow(new IllegalStateException("Invalid transition"));

        mockMvc.perform(put("/api/orders/o-3/status")
                        .with(httpBasic("api-user", "change-me"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"status":"DELIVERED","note":"invalid"}
                            """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Business rule violation"));
    }

}
