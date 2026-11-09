package fr.wael.reactiveorders.application;

import fr.wael.reactiveorders.domain.Order;
import java.util.List;

public record OrderPage(List<Order> items, int page, int size, boolean hasNext) {
    public OrderPage { items = List.copyOf(items); }
}
